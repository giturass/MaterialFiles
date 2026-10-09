/* Keep 7-Zip's BSTR allocations under the same limit without changing vendored source. */
#include <cstdlib>
#include "sevenzip_memory.h"
#define malloc mf7z_malloc
#define realloc mf7z_realloc
#define free mf7z_free
#include "7zip/CPP/Common/MyWindows.cpp"
