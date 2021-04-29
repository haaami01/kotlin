/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.ir

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*
import org.gradle.api.tasks.incremental.IncrementalTaskInputs
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.compilerRunner.GradleCompilerEnvironment
import org.jetbrains.kotlin.compilerRunner.GradleCompilerRunner
import org.jetbrains.kotlin.compilerRunner.OutputItemsCollectorImpl
import org.jetbrains.kotlin.gradle.dsl.KotlinJsOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJsOptionsImpl
import org.jetbrains.kotlin.gradle.dsl.copyFreeCompilerArgsToArgs
import org.jetbrains.kotlin.gradle.logging.GradlePrintingMessageCollector
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.PropertiesProvider
import org.jetbrains.kotlin.gradle.report.ReportingSettings
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode.DEVELOPMENT
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode.PRODUCTION
import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile
import org.jetbrains.kotlin.gradle.tasks.SourceRoots
import org.jetbrains.kotlin.gradle.utils.getAllDependencies
import org.jetbrains.kotlin.gradle.utils.getCacheDirectory
import org.jetbrains.kotlin.gradle.utils.getDependenciesCacheDirectories
import org.jetbrains.kotlin.gradle.utils.newFileProperty
import org.jetbrains.kotlin.incremental.ChangedFiles
import java.io.File
import javax.inject.Inject

@CacheableTask
open class KotlinJsIrLink @Inject constructor(
    objectFactory: ObjectFactory
) : Kotlin2JsCompile(objectFactory) {
    @Transient
    @get:Internal
    internal lateinit var compilation: KotlinCompilation<*>

    @get:Input
    internal val incrementalJsIr: Boolean = PropertiesProvider(project).incrementalJsIr

    // Link tasks are not affected by compiler plugin
    override val pluginClasspath: FileCollection = project.objects.fileCollection()

    @Input
    lateinit var mode: KotlinJsBinaryMode

    // Not check sources, only klib module
    @Internal
    override fun getSource(): FileTree = super.getSource()

    override val kotlinOptions: KotlinJsOptions = KotlinJsOptionsImpl()

    private val buildDir = project.buildDir

    private val compileClasspathConfiguration by lazy {
        project.configurations.getByName(compilation.compileDependencyConfigurationName)
    }

    @get:SkipWhenEmpty
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    internal val entryModule: File by lazy {
        File(
            (taskData.compilation as KotlinJsIrCompilation)
                .output
                .classesDirs
                .asPath
        )
    }

    override fun skipCondition(inputs: IncrementalTaskInputs): Boolean {
        return !inputs.isIncremental && !entryModule.exists()
    }

    override fun getDestinationDir(): File {
        return if (kotlinOptions.outputFile == null) {
            super.getDestinationDir()
        } else {
            outputFile.parentFile
        }
    }

    @OutputFile
    val outputFileProperty: RegularFileProperty = project.newFileProperty {
        outputFile
    }

    override fun callCompilerAsync(args: K2JSCompilerArguments, sourceRoots: SourceRoots, changedFiles: ChangedFiles) {
        if (incrementalJsIr) {
            val visitedDependencies = mutableSetOf<ResolvedDependency>()
            val visitedFiles = mutableSetOf<File>()
            val visitedCompilations = mutableSetOf<KotlinCompilation<*>>()
            val allCacheDirectories = mutableSetOf<File>()

            val cacheArgs = visitAssociated(
                compilation,
                visitedDependencies,
                visitedFiles,
                visitedCompilations,
                allCacheDirectories
            )

            args.cacheDirectories = cacheArgs.joinToString(File.pathSeparator) {
                it.normalize().absolutePath
            }
        }
        super.callCompilerAsync(args, sourceRoots, changedFiles)
    }

    private fun visitAssociated(
        associated: KotlinCompilation<*>,
        visitedDependencies: MutableSet<ResolvedDependency>,
        visitedFiles: MutableSet<File>,
        visitedCompilations: MutableSet<KotlinCompilation<*>>,
        visitedCacheDirectories: MutableSet<File>
    ): List<File> {
        if (associated in visitedCompilations) return emptyList()
        visitedCompilations.add(associated)

        val prevRes = associated.associateWith
            .flatMap { compilation ->
                visitAssociated(
                    compilation,
                    visitedDependencies,
                    visitedFiles,
                    visitedCompilations,
                    visitedCacheDirectories
                )
            }

        return prevRes + CacheBuilder(
            buildDir,
            project.configurations.getByName(associated.compileDependencyConfigurationName),
            associated.output.classesDirs,
            kotlinOptions,
            libraryFilter,
            compilerRunner,
            { createCompilerArgs() },
            computedCompilerClasspath,
            logger,
            objects.fileCollection(),
            reportingSettings,
            visitedDependencies,
            visitedFiles,
            prevRes
        )
            .buildCompilerArgs()
            .filter { it !in visitedCacheDirectories }
            .also { visitedCacheDirectories.addAll(it) }
    }

    override fun setupCompilerArgs(args: K2JSCompilerArguments, defaultsOnly: Boolean, ignoreClasspathResolutionErrors: Boolean) {
        when (mode) {
            PRODUCTION -> {
                kotlinOptions.configureOptions(ENABLE_DCE, GENERATE_D_TS)
            }
            DEVELOPMENT -> {
                kotlinOptions.configureOptions(GENERATE_D_TS)
            }
        }
        super.setupCompilerArgs(args, defaultsOnly, ignoreClasspathResolutionErrors)
    }

    private fun KotlinJsOptions.configureOptions(vararg additionalCompilerArgs: String) {
        freeCompilerArgs += additionalCompilerArgs.toList() +
                PRODUCE_JS +
                "$ENTRY_IR_MODULE=${entryModule.canonicalPath}"
    }
}

internal class CacheBuilder(
    private val buildDir: File,
    private val compileClasspath: Configuration,
    private val additionalForResolve: FileCollection?,
    private val kotlinOptions: KotlinJsOptions,
    private val libraryFilter: (File) -> Boolean,
    private val compilerRunner: GradleCompilerRunner,
    private val compilerArgsFactory: () -> K2JSCompilerArguments,
    private val computedCompilerClasspath: List<File>,
    private val logger: Logger,
    private val outputFiles: FileCollection,
    private val reportingSettings: ReportingSettings,
    private val visitedDependencies: MutableSet<ResolvedDependency>,
    private val visitedFiles: MutableSet<File>,
    private val associatedCaches: List<File>
) {
    val rootCacheDirectory by lazy {
        buildDir.resolve("klib/cache")
    }

    fun buildCompilerArgs(): List<File> {

        val allCacheDirectories = mutableListOf<File>()
        val visitedDependenciesForCache = mutableSetOf<ResolvedDependency>()

        compileClasspath.resolvedConfiguration.firstLevelModuleDependencies
            .forEach { dependency ->
                ensureDependencyCached(
                    dependency,
                    visitedDependencies,
                    visitedFiles
                )
                if (dependency !in visitedDependenciesForCache) {
                    (listOf(dependency) + getAllDependencies(dependency))
                        .filter { it !in visitedDependenciesForCache }
                        .forEach { dependencyForCache ->
                            visitedDependenciesForCache.add(dependencyForCache)
                            val cacheDirectory = getCacheDirectory(rootCacheDirectory, dependencyForCache)
                            if (cacheDirectory.exists()) {
                                allCacheDirectories.add(cacheDirectory)
                            }
                        }
                }
            }

        additionalForResolve?.files?.forEach { file ->
            val cacheDirectory = rootCacheDirectory.resolve(file.name)
            cacheDirectory.mkdirs()
            runCompiler(
                file,
                visitedFiles,
                compileClasspath.files,
                cacheDirectory,
                (allCacheDirectories + associatedCaches).distinct()
            )
            allCacheDirectories.add(cacheDirectory)
        }

        return allCacheDirectories
    }

    private fun ensureDependencyCached(
        dependency: ResolvedDependency,
        visitedDependency: MutableSet<ResolvedDependency>,
        visitedFiles: MutableSet<File>
    ) {
        if (dependency in visitedDependency) return
        visitedDependency.add(dependency)

        dependency.children
            .forEach { ensureDependencyCached(it, visitedDependency, visitedFiles) }

        val artifactsToAddToCache = dependency.moduleArtifacts
            .filter { libraryFilter(it.file) }

        if (artifactsToAddToCache.isEmpty()) return

        val dependenciesCacheDirectories = getDependenciesCacheDirectories(
            rootCacheDirectory,
            dependency
        ) ?: return

        val cacheDirectory = getCacheDirectory(rootCacheDirectory, dependency)
        cacheDirectory.mkdirs()

        for (library in artifactsToAddToCache) {
            runCompiler(
                library.file,
                visitedFiles,
                getAllDependencies(dependency)
                    .flatMap { it.moduleArtifacts }
                    .map { it.file },
                cacheDirectory,
                dependenciesCacheDirectories
            )
        }
    }

    fun runCompiler(
        file: File,
        visitedFiles: MutableSet<File>,
        dependencies: Collection<File>,
        cacheDirectory: File,
        dependenciesCacheDirectories: Collection<File>
    ) {
        if (file in visitedFiles) return
        val compilerArgs = compilerArgsFactory()
        kotlinOptions.copyFreeCompilerArgsToArgs(compilerArgs)
        compilerArgs.freeArgs = compilerArgs.freeArgs
            .filterNot { arg ->
                IGNORED_ARGS.any {
                    arg.startsWith(it)
                }
            }

        visitedFiles.add(file)
        compilerArgs.includes = file.normalize().absolutePath
        compilerArgs.outputFile = cacheDirectory.normalize().absolutePath
        if (dependenciesCacheDirectories.isNotEmpty()) {
            compilerArgs.cacheDirectories = dependenciesCacheDirectories.joinToString(File.pathSeparator)
        }
        compilerArgs.irBuildCache = true

        compilerArgs.libraries = dependencies
            .filter { it.exists() && libraryFilter(it) }
            .distinct()
            .filterNot { it == file }
            .joinToString(File.pathSeparator) { it.normalize().absolutePath }

        val messageCollector = GradlePrintingMessageCollector(logger, false)
        val outputItemCollector = OutputItemsCollectorImpl()
        val environment = GradleCompilerEnvironment(
            computedCompilerClasspath,
            messageCollector,
            outputItemCollector,
            outputFiles = outputFiles,
            reportingSettings = reportingSettings
        )

        compilerRunner
            .runJsCompilerAsync(
                emptyList(),
                emptyList(),
                compilerArgs,
                environment
            )
    }

    companion object {
        private val IGNORED_ARGS = listOf(
            ENTRY_IR_MODULE,
            PRODUCE_JS,
            PRODUCE_UNZIPPED_KLIB,
            ENABLE_DCE,
            GENERATE_D_TS
        )
    }
}

typealias A = Unit