/*
 * Copyright 2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "MediumPage.hpp"

#include <atomic>
#include <cstdint>

#include "CustomLogging.hpp"
#include "CustomAllocConstants.hpp"
#include "GCApi.hpp"

namespace kotlin::alloc {

MediumPage* MediumPage::Create(uint32_t cellCount) noexcept {
    CustomAllocInfo("MediumPage::Create(%u)", cellCount);
    RuntimeAssert(cellCount < MEDIUM_PAGE_CELL_COUNT, "cellCount is too large for medium page");
    return new (SafeAlloc(MEDIUM_PAGE_SIZE)) MediumPage(cellCount);
}

void MediumPage::Destroy() noexcept {
    std_support::free(this);
}

MediumPage::MediumPage(uint32_t cellCount) noexcept : curBlock_(cells_) {
    cells_[0] = Cell(0); // Size 0 ensures any actual use would break
    cells_[1] = Cell(MEDIUM_PAGE_CELL_COUNT - 1);
}

uint8_t* MediumPage::TryAllocate(uint32_t blockSize) noexcept {
    CustomAllocDebug("MediumPage@%p::TryAllocate(%u)", this, blockSize);
    // +1 accounts for header, since cell->size also includes header cell
    uint32_t cellsNeeded = blockSize + 1;
    uint8_t* block = curBlock_->TryAllocate(cellsNeeded);
    if (block) return block;
    UpdateCurBlock(cellsNeeded);
    return curBlock_->TryAllocate(cellsNeeded);
}

bool MediumPage::Sweep() noexcept {
    CustomAllocDebug("MediumPage@%p::Sweep()", this);
    Cell* end = cells_ + MEDIUM_PAGE_CELL_COUNT;
    bool alive = false;
    for (Cell* block = cells_ + 1; block != end; block = block->Next()) {
        if (block->isAllocated_) {
            if (TryResetMark(block->data_)) {
                alive = true;
            } else {
                block->Deallocate();
            }
        }
    }
    Cell* maxBlock = cells_; // size 0 block
    for (Cell* block = cells_ + 1; block != end; block = block->Next()) {
        if (block->isAllocated_) continue;
        while (block->Next() != end && !block->Next()->isAllocated_) {
            block->size_ += block->Next()->size_;
        }
        if (block->size_ > maxBlock->size_) maxBlock = block;
    }
    curBlock_ = maxBlock;
    return alive;
}

void MediumPage::UpdateCurBlock(uint32_t cellsNeeded) noexcept {
    CustomAllocDebug("MediumPage@%p::UpdateCurBlock(%u)", this, cellsNeeded);
    if (curBlock_ == cells_) curBlock_ = cells_ + 1; // only used as a starting point
    Cell* end = cells_ + MEDIUM_PAGE_CELL_COUNT;
    Cell* maxBlock = cells_; // size 0 block
    for (Cell* block = curBlock_; block != end; block = block->Next()) {
        if (!block->isAllocated_ && block->size_ > maxBlock->size_) {
            maxBlock = block;
            if (block->size_ >= cellsNeeded) {
                curBlock_ = maxBlock;
                return;
            }
        }
    }
    CustomAllocDebug("MediumPage@%p::UpdateCurBlock: starting from beginning", this);
    for (Cell* block = cells_ + 1; block != curBlock_; block = block->Next()) {
        if (!block->isAllocated_ && block->size_ > maxBlock->size_) {
            maxBlock = block;
            if (block->size_ >= cellsNeeded) {
                curBlock_ = maxBlock;
                return;
            }
        }
    }
    curBlock_ = maxBlock;
}

bool MediumPage::CheckInvariants() noexcept {
    if (curBlock_ < cells_ || curBlock_ >= cells_ + MEDIUM_PAGE_CELL_COUNT) return false;
    for (Cell* cur = cells_ + 1;; cur = cur->Next()) {
        if (cur->Next() <= cur) return false;
        if (cur->Next() > cells_ + MEDIUM_PAGE_CELL_COUNT) return false;
        if (cur->Next() == cells_ + MEDIUM_PAGE_CELL_COUNT) return true;
    }
}

} // namespace kotlin::alloc
