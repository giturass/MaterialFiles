/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */
#include "sevenzip_memory.h"

#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdlib>
#include <limits>
#include <new>
#include <pthread.h>

namespace materialfiles {

struct MemoryState {
    explicit MemoryState(uint64_t maximum) : limit(maximum) {}
    const uint64_t limit;
    std::atomic<uint64_t> used{0};
    std::atomic<unsigned> references{1};
    std::atomic<bool> failed{false};
};

static thread_local MemoryState *currentBudget = nullptr;

static void retain(MemoryState *state) {
    if (state) {
        state->references.fetch_add(1, std::memory_order_relaxed);
    }
}

static void release(MemoryState *state) {
    if (state && state->references.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        state->~MemoryState();
        std::free(state);
    }
}

static bool reserve(MemoryState *state, size_t size) {
    if (!state) {
        return true;
    }
    uint64_t previous = state->used.load(std::memory_order_relaxed);
    do {
        if (size > state->limit || previous > state->limit - size) {
            state->failed.store(true, std::memory_order_relaxed);
            errno = ENOMEM;
            return false;
        }
    } while (!state->used.compare_exchange_weak(previous, previous + size,
            std::memory_order_relaxed));
    return true;
}

static void unreserve(MemoryState *state, size_t size) {
    if (state) {
        state->used.fetch_sub(size, std::memory_order_relaxed);
    }
}

MemoryBudget::MemoryBudget(uint64_t limit) {
    void *memory = std::malloc(sizeof(MemoryState));
    if (!memory) {
        throw std::bad_alloc();
    }
    state_ = new (memory) MemoryState(limit);
}

MemoryBudget::~MemoryBudget() {
    release(state_);
}

bool MemoryBudget::exceeded() const {
    return state_->failed.load(std::memory_order_relaxed);
}

void MemoryBudget::resetFailure() {
    state_->failed.store(false, std::memory_order_relaxed);
}

MemoryBudget::Scope::Scope(MemoryBudget &budget)
        : previous_(currentBudget), active_(budget.state_) {
    retain(active_);
    currentBudget = active_;
}

MemoryBudget::Scope::~Scope() {
    currentBudget = previous_;
    release(active_);
}

struct alignas(std::max_align_t) Allocation {
    MemoryState *budget;
    size_t size;
    // The official AES and codec buffers use posix_memalign. Their header can be offset from
    // malloc's base, but free must always receive that original, correctly tagged pointer.
    void *base;
};

static void *allocate(size_t size, size_t alignment) {
    MemoryState *budget = currentBudget;
    if (alignment < alignof(Allocation)) {
        alignment = alignof(Allocation);
    }
    const size_t maximum = std::numeric_limits<size_t>::max();
    if (alignment - 1 > maximum - sizeof(Allocation)
            || size > maximum - sizeof(Allocation) - (alignment - 1)
            || !reserve(budget, size)) {
        errno = ENOMEM;
        return nullptr;
    }
    void *base = std::malloc(sizeof(Allocation) + alignment - 1 + size);
    if (!base) {
        unreserve(budget, size);
        return nullptr;
    }
    const uintptr_t address = (reinterpret_cast<uintptr_t>(base) + sizeof(Allocation)
            + alignment - 1) & ~(static_cast<uintptr_t>(alignment) - 1);
    Allocation *allocation = reinterpret_cast<Allocation *>(address) - 1;
    allocation->budget = budget;
    allocation->size = size;
    allocation->base = base;
    retain(budget);
    return reinterpret_cast<void *>(address);
}

struct ThreadStart {
    void *(*function)(void *);
    void *argument;
    MemoryState *budget;
};

static void *threadStart(void *argument) {
    ThreadStart *start = static_cast<ThreadStart *>(argument);
    const ThreadStart copy = *start;
    std::free(start);
    currentBudget = copy.budget;
    void *result = copy.function(copy.argument);
    currentBudget = nullptr;
    release(copy.budget);
    return result;
}

}

extern "C" void *mf7z_malloc(size_t size) {
    return materialfiles::allocate(size, alignof(std::max_align_t));
}

extern "C" int mf7z_posix_memalign(void **address, size_t alignment, size_t size) {
    if (alignment < sizeof(void *) || (alignment & (alignment - 1)) != 0) {
        return EINVAL;
    }
    void *result = materialfiles::allocate(size, alignment);
    if (!result) {
        return ENOMEM;
    }
    *address = result;
    return 0;
}

extern "C" void mf7z_free(void *address) {
    using namespace materialfiles;
    if (!address) {
        return;
    }
    Allocation *allocation = static_cast<Allocation *>(address) - 1;
    MemoryState *budget = allocation->budget;
    unreserve(budget, allocation->size);
    std::free(allocation->base);
    release(budget);
}

extern "C" void *mf7z_realloc(void *address, size_t size) {
    using namespace materialfiles;
    if (!address) {
        return mf7z_malloc(size);
    }
    if (size == 0) {
        mf7z_free(address);
        return nullptr;
    }
    Allocation *allocation = static_cast<Allocation *>(address) - 1;
    MemoryState *budget = allocation->budget;
    const size_t previousSize = allocation->size;
    const size_t offset = reinterpret_cast<char *>(allocation)
            - static_cast<char *>(allocation->base);
    const size_t overhead = sizeof(Allocation) + offset;
    if (size > std::numeric_limits<size_t>::max() - overhead
            || (size > previousSize && !reserve(budget, size - previousSize))) {
        errno = ENOMEM;
        return nullptr;
    }
    void *base = std::realloc(allocation->base, overhead + size);
    if (!base) {
        if (size > previousSize) {
            unreserve(budget, size - previousSize);
        }
        return nullptr;
    }
    if (size < previousSize) {
        unreserve(budget, previousSize - size);
    }
    Allocation *replacement = reinterpret_cast<Allocation *>(static_cast<char *>(base) + offset);
    replacement->base = base;
    replacement->size = size;
    return replacement + 1;
}

// These definitions are local to libsevenzip.so through sevenzip.map. The static C++ runtime
// consequently uses the same allocator as the official C codecs and archive metadata buffers.
void *operator new(size_t size) {
    void *result = mf7z_malloc(size == 0 ? 1 : size);
    if (!result) {
        throw std::bad_alloc();
    }
    return result;
}
void *operator new[](size_t size) { return ::operator new(size); }
void operator delete(void *address) noexcept { mf7z_free(address); }
void operator delete[](void *address) noexcept { mf7z_free(address); }
void operator delete(void *address, size_t) noexcept { mf7z_free(address); }
void operator delete[](void *address, size_t) noexcept { mf7z_free(address); }

extern "C" int __real_pthread_create(pthread_t *, const pthread_attr_t *,
        void *(*)(void *), void *);

extern "C" int __wrap_pthread_create(pthread_t *thread, const pthread_attr_t *attributes,
        void *(*function)(void *), void *argument) {
    using namespace materialfiles;
    ThreadStart *start = static_cast<ThreadStart *>(std::malloc(sizeof(ThreadStart)));
    if (!start) {
        return ENOMEM;
    }
    start->function = function;
    start->argument = argument;
    start->budget = currentBudget;
    retain(start->budget);
    const int result = __real_pthread_create(thread, attributes, threadStart, start);
    if (result != 0) {
        release(start->budget);
        std::free(start);
    }
    return result;
}
