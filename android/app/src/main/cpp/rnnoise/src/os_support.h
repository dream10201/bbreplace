#ifndef OS_SUPPORT_H
#define OS_SUPPORT_H

#include <string.h>

#ifndef OPUS_INLINE
# if defined(_MSC_VER)
#  define OPUS_INLINE __forceinline
# else
#  define OPUS_INLINE inline __attribute__((always_inline))
# endif
#endif

#ifndef OPUS_CLEAR
#define OPUS_CLEAR(dst, n) memset((dst), 0, (n) * sizeof(*(dst)))
#endif

#endif
