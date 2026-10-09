/* Copyright (c) 2026 Material Files contributors. All Rights Reserved. */
#ifndef MATERIALFILES_SEVENZIP_MEMORY_H
#define MATERIALFILES_SEVENZIP_MEMORY_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif
void *mf7z_malloc(size_t size);
void *mf7z_realloc(void *address, size_t size);
int mf7z_posix_memalign(void **address, size_t alignment, size_t size);
void mf7z_free(void *address);
#ifdef __cplusplus
}

namespace materialfiles {

struct MemoryState;

/** Limits this archive's native allocations, including allocations on codec worker threads. */
class MemoryBudget {
public:
    explicit MemoryBudget(uint64_t limit);
    ~MemoryBudget();
    MemoryBudget(const MemoryBudget &) = delete;
    MemoryBudget &operator=(const MemoryBudget &) = delete;
    bool exceeded() const;
    void resetFailure();

    class Scope {
    public:
        explicit Scope(MemoryBudget &budget);
        ~Scope();
        Scope(const Scope &) = delete;
        Scope &operator=(const Scope &) = delete;
    private:
        MemoryState *previous_;
        MemoryState *active_;
    };

private:
    MemoryState *state_;
};

}
#endif
#endif
