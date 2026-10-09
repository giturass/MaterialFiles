/* Use the official allocator, with its actual allocations charged to the archive's budget. */
#include <stdlib.h>
#include "sevenzip_memory.h"
#define malloc mf7z_malloc
#define realloc mf7z_realloc
#define posix_memalign mf7z_posix_memalign
#define free mf7z_free
#include "7zip/C/Alloc.c"
