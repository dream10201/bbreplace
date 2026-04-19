#ifndef RTC_BASE_SYSTEM_ARCH_H_
#define RTC_BASE_SYSTEM_ARCH_H_

#if defined(__aarch64__) || defined(__arm__)
#define WEBRTC_ARCH_ARM_FAMILY
#endif

#if defined(__aarch64__)
#define WEBRTC_ARCH_64_BITS
#endif

#endif  // RTC_BASE_SYSTEM_ARCH_H_
