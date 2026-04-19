#ifndef RTC_BASE_CHECKS_H_
#define RTC_BASE_CHECKS_H_

#include <assert.h>

#define RTC_CHECK(condition) assert(condition)
#define RTC_DCHECK(condition) assert(condition)
#define RTC_CHECK_GT(a, b) assert((a) > (b))
#define RTC_CHECK_GE(a, b) assert((a) >= (b))
#define RTC_CHECK_LT(a, b) assert((a) < (b))
#define RTC_CHECK_LE(a, b) assert((a) <= (b))
#define RTC_CHECK_EQ(a, b) assert((a) == (b))
#define RTC_CHECK_NE(a, b) assert((a) != (b))
#define RTC_DCHECK_GT(a, b) assert((a) > (b))
#define RTC_DCHECK_GE(a, b) assert((a) >= (b))
#define RTC_DCHECK_LT(a, b) assert((a) < (b))
#define RTC_DCHECK_LE(a, b) assert((a) <= (b))
#define RTC_DCHECK_EQ(a, b) assert((a) == (b))
#define RTC_DCHECK_NE(a, b) assert((a) != (b))

#endif  // RTC_BASE_CHECKS_H_
