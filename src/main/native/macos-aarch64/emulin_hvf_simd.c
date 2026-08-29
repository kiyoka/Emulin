// ----------------------------------------
//  Hypervisor.framework SIMD ABI shim (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
#include <Hypervisor/Hypervisor.h>
#include <stdint.h>
#include <string.h>

// hv_simd_fp_uchar16_t is a Clang vector passed by value. Java FFM has no
// vector layout, so expose an ordinary pointer/scalar ABI at this boundary.
hv_return_t emulin_hv_vcpu_get_simd_fp_reg(hv_vcpu_t vcpu,
                                            hv_simd_fp_reg_t reg,
                                            uint64_t *low,
                                            uint64_t *high) {
  hv_simd_fp_uchar16_t value;
  hv_return_t result = hv_vcpu_get_simd_fp_reg(vcpu, reg, &value);
  if (result == HV_SUCCESS) {
    uint64_t words[2];
    memcpy(words, &value, sizeof(words));
    *low = words[0];
    *high = words[1];
  }
  return result;
}

hv_return_t emulin_hv_vcpu_set_simd_fp_reg(hv_vcpu_t vcpu,
                                            hv_simd_fp_reg_t reg,
                                            uint64_t low,
                                            uint64_t high) {
  uint64_t words[2] = { low, high };
  hv_simd_fp_uchar16_t value;
  memcpy(&value, words, sizeof(value));
  return hv_vcpu_set_simd_fp_reg(vcpu, reg, value);
}
