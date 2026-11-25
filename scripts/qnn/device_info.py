#!/usr/bin/env python3
from dataclasses import dataclass
from enum import IntEnum, unique

"""
Qualcomm Snapdragon 8 Elite (SM8750 / Samsung Galaxy S25 Ultra):
soc_id = 69, dsp_arch = v79, vtcm_size (MB) = 8
"""


@unique
class Chipset(IntEnum):
    SM8750 = 69  # Samsung Galaxy S25 Ultra (Snapdragon 8 Elite, Hexagon V79)


@unique
class HtpArch(IntEnum):
    v79 = 79


@dataclass
class HtpInfo:
    arch: HtpArch
    vtcm_size_in_mb: int


@dataclass
class SocInfo:
    model: Chipset
    info: HtpInfo


soc_info_list = [
    SocInfo(Chipset.SM8750, HtpInfo(HtpArch.v79, 8)),
]

soc_info_dict = {soc.model.name: soc for soc in soc_info_list}


if __name__ == "__main__":
    for soc in soc_info_list:
        print(
            soc.model.name,
            "soc_id\t",
            soc.model.value,
            "dsp_arch\t",
            soc.info.arch.name,
            "vtcm_size (MB)\t",
            soc.info.vtcm_size_in_mb,
        )
