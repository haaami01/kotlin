/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jps.build

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.RunAll
import com.intellij.util.ThrowableRunnable
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.module.JpsModule
import org.jetbrains.jps.util.JpsPathUtil
import org.jetbrains.kotlin.cli.common.CompilerSystemProperties
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.config.CompilerSettings
import org.jetbrains.kotlin.config.KotlinFacetSettings
import org.jetbrains.kotlin.jps.model.JpsKotlinFacetModuleExtension
import org.jetbrains.kotlin.platform.js.JsPlatforms
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File
import java.nio.file.Paths

abstract class KotlinJpsBuildTestBase : AbstractKotlinJpsBuildTestCase() {
    protected lateinit var originalProjectDir: File
    protected val expectedOutputFile: File
        get() = File(originalProjectDir, "expected-output.txt")

    override fun setUp() {
        super.setUp()
        val currentTestMethod = this::class.members.firstOrNull { it.name == "test" + getTestName(false) }
        val workingDirFromAnnotation = currentTestMethod?.annotations?.filterIsInstance<WorkingDir>()?.firstOrNull()?.name
        val projDirPath = Paths.get(
            TEST_DATA_PATH,
            "general",
            workingDirFromAnnotation ?: getTestName(false)
        )
        originalProjectDir = projDirPath.toFile()
        workDir = copyTestDataToTmpDir(originalProjectDir)
        orCreateProjectDir
    }

    protected open fun copyTestDataToTmpDir(testDataDir: File): File {
        assert(testDataDir.exists()) { "Cannot find source folder " + testDataDir.absolutePath }
        val tmpDir = FileUtil.createTempDirectory("kjbtb-jps-build", null)
        FileUtil.copyDir(testDataDir, tmpDir)
        return tmpDir
    }

    override fun tearDown() {
        RunAll(
            ThrowableRunnable { workDir.deleteRecursively() },
            ThrowableRunnable { super.tearDown() }
        ).run()
    }

    override fun doGetProjectDir(): File = workDir

    annotation class WorkingDir(val name: String)

    enum class LibraryDependency {
        NONE,
        JVM_MOCK_RUNTIME,
        JVM_FULL_RUNTIME,
        JS_STDLIB_WITHOUT_FACET,
        JS_STDLIB,
        LOMBOK
    }

    protected fun initProject(libraryDependency: LibraryDependency = LibraryDependency.NONE) {
        addJdk(JDK_NAME)
        loadProject(workDir.absolutePath + File.separator + PROJECT_NAME + ".ipr")

        when (libraryDependency) {
            LibraryDependency.NONE -> {}
            LibraryDependency.JVM_MOCK_RUNTIME -> addKotlinMockRuntimeDependency()
            LibraryDependency.JVM_FULL_RUNTIME -> addKotlinStdlibDependency()
            LibraryDependency.JS_STDLIB_WITHOUT_FACET -> addKotlinJavaScriptStdlibDependency()
            LibraryDependency.JS_STDLIB -> {
                addKotlinJavaScriptStdlibDependency()
                setupKotlinJSFacet()
            }
            LibraryDependency.LOMBOK -> {
                addKotlinLombokDependency()
                setupKotlinLombokFacet()
            }
        }
    }

    protected fun setupKotlinJSFacet() {
        myProject.modules.forEach {
            val facet = KotlinFacetSettings()
            facet.compilerArguments = K2JSCompilerArguments().apply {
                useDeprecatedLegacyCompiler = true
                // TODO: It will be deleted after all of our internal vendors will use the new Kotlin/JS compiler
                CompilerSystemProperties.KOTLIN_JS_COMPILER_LEGACY_FORCE_ENABLED.value = "true"
            }
            facet.targetPlatform = JsPlatforms.defaultJsPlatform

            it.container.setChild(
                JpsKotlinFacetModuleExtension.KIND,
                JpsKotlinFacetModuleExtension(facet)
            )
        }
    }

    private fun setupKotlinLombokFacet() {
        myProject.modules.forEach {
            val facet = KotlinFacetSettings()
            facet.useProjectSettings = false
            facet.compilerSettings = CompilerSettings().also {
                it.additionalArguments = "-Xallow-no-source-files -Xplugin=${PathUtil.kotlinPathsForDistDirectory.lombokPluginJarPath}"
            }

            it.container.setChild(
                JpsKotlinFacetModuleExtension.KIND,
                JpsKotlinFacetModuleExtension(facet)
            )
        }
    }

    companion object {
        const val JDK_NAME = "IDEA_JDK"
        const val PROJECT_NAME = "kotlinProject"

        @JvmStatic
        protected fun assertFilesExistInOutput(module: JpsModule, vararg relativePaths: String) {
            for (path in relativePaths) {
                val outputFile = findFileInOutputDir(module, path)
                assertTrue("Output not written: " + outputFile.absolutePath + "\n Directory contents: \n" + dirContents(
                    outputFile.parentFile
                ), outputFile.exists())
            }
        }

        @JvmStatic
        protected fun findFileInOutputDir(module: JpsModule, relativePath: String): File {
            val outputUrl = JpsJavaExtensionService.getInstance().getOutputUrl(module, false)
            assertNotNull(outputUrl)
            val outputDir = File(JpsPathUtil.urlToPath(outputUrl))
            return File(outputDir, relativePath)
        }

        @JvmStatic
        protected fun assertFilesNotExistInOutput(module: JpsModule, vararg relativePaths: String) {
            val outputUrl = JpsJavaExtensionService.getInstance().getOutputUrl(module, false)
            assertNotNull(outputUrl)
            val outputDir = File(JpsPathUtil.urlToPath(outputUrl))
            for (path in relativePaths) {
                val outputFile = File(outputDir, path)
                assertFalse("Output directory \"" + outputFile.absolutePath + "\" contains \"" + path + "\"", outputFile.exists())
            }
        }

        private fun dirContents(dir: File): String {
            val files = dir.listFiles() ?: return "<not found>"
            val builder = StringBuilder()
            for (file in files) {
                builder.append(" * ").append(file.name).append("\n")
            }
            return builder.toString()
        }
    }
}