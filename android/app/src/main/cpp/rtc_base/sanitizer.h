#ifndef RTC_BASE_SANITIZER_H_
#define RTC_BASE_SANITIZER_H_

#define RTC_NO_SANITIZE(x)

static inline void rtc_MsanCheckInitialized(
    const void* /* data */,
    int /* element_size */,
    int /* count */) {}

#endif  // RTC_BASE_SANITIZER_H_
