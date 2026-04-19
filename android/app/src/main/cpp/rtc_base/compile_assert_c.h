#ifndef RTC_BASE_COMPILE_ASSERT_C_H_
#define RTC_BASE_COMPILE_ASSERT_C_H_

#define RTC_COMPILE_ASSERT(expr) typedef char rtc_compile_assert[(expr) ? 1 : -1]

#endif  // RTC_BASE_COMPILE_ASSERT_C_H_
