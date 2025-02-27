import re
import logging
from typing import Optional, Tuple

from ...globals import RESERVED_STORAGE_GB_PER_CORE

log = logging.getLogger('resource_utils')

# https://docs.microsoft.com/en-us/azure/virtual-machines/vm-naming-conventions
MACHINE_TYPE_REGEX = re.compile(
    r'(?P<typ>[^_]+)_(?P<family>[A-Z])(?P<sub_family>[^\d])?(?P<cpu>\d+)(-(?P<constrained_cpu>\d+))?(?P<additive_features>[^_]+)?(_((?P<accelerator_type>[^_]+)_)?(?P<version>.*)?)?'
)

AZURE_MAX_PERSISTENT_SSD_SIZE_GIB = 32 * 1024


azure_local_ssd_size_per_core_by_worker_type = {
    'D': 37.5,
    'E': 37.5,
    'F': 8,
}


azure_valid_cores_from_worker_type = {
    'D': [2, 4, 8, 16, 32, 48, 64],
    'E': [2, 4, 8, 16, 20, 32, 48, 64],
    'F': [2, 4, 8, 16, 32, 48, 64, 72],
}


azure_valid_machine_types = []
for cores in azure_valid_cores_from_worker_type['D']:
    azure_valid_machine_types.append(f'Standard_D{cores}d_v4')
    azure_valid_machine_types.append(f'Standard_D{cores}s_v4')
for cores in azure_valid_cores_from_worker_type['E']:
    azure_valid_machine_types.append(f'Standard_E{cores}d_v4')
    azure_valid_machine_types.append(f'Standard_E{cores}s_v4')
for cores in azure_valid_cores_from_worker_type['F']:
    azure_valid_machine_types.append(f'Standard_F{cores}s_v2')


azure_memory_to_worker_type = {
    'lowmem': 'F',
    'standard': 'D',
    'highmem': 'E'
}


class MachineTypeParts:
    @staticmethod
    def from_dict(data: dict) -> 'MachineTypeParts':
        constrained_cpu = data['constrained_cpu']
        if constrained_cpu is not None:
            constrained_cpu = int(constrained_cpu)
        return MachineTypeParts(data['typ'], data['family'], data['sub_family'],
                                int(data['cpu']), constrained_cpu,
                                data['additive_features'], data['accelerator_type'],
                                data['version'])

    def __init__(self, typ: str, family: str, sub_family: Optional[str], cores: int,
                 constrained_cpu: Optional[int], additive_features: Optional[str],
                 accelerator_type: Optional[str], version: Optional[str]):
        self.typ = typ
        self.family = family
        self.sub_family = sub_family
        self.cores = cores
        self.constrained_cpu = constrained_cpu
        self.additive_features = additive_features
        self.accelerator_type = accelerator_type
        self.version = version


def azure_machine_type_to_parts(machine_type: str) -> Optional[MachineTypeParts]:
    match = MACHINE_TYPE_REGEX.fullmatch(machine_type)
    if match is None:
        return match
    return MachineTypeParts.from_dict(match.groupdict())


def azure_machine_type_to_worker_type_and_cores(machine_type: str) -> Tuple[str, int]:
    maybe_machine_type_parts = azure_machine_type_to_parts(machine_type)
    if maybe_machine_type_parts is None:
        raise ValueError(f'bad machine_type: {machine_type}')
    # local_ssd = maybe_machine_type_parts.additive_features == 'd' or maybe_machine_type_parts.family == 'F'
    return (maybe_machine_type_parts.family, maybe_machine_type_parts.cores)


def azure_worker_properties_to_machine_type(worker_type: str, cores: int, local_ssd_data_disk: bool) -> str:
    if worker_type == 'F':
        return f'Standard_F{cores}s_v2'

    if local_ssd_data_disk:
        additive_features = 'd'
    else:
        additive_features = 's'

    machine_type = f'Standard_{worker_type}{cores}{additive_features}_v4'
    return machine_type


def azure_worker_memory_per_core_mib(worker_type: str) -> int:
    if worker_type == 'F':
        m = 2048
    elif worker_type == 'D':
        m = 4096
    else:
        assert worker_type == 'E'
        m = 8192
    return m


def azure_local_ssd_size(worker_type: str, cores: int) -> int:
    return int(cores * azure_local_ssd_size_per_core_by_worker_type[worker_type])


def azure_unreserved_worker_data_disk_size_gib(local_ssd_data_disk, external_data_disk_size_gib,
                                               worker_cores, worker_type):
    reserved_image_size = 30
    reserved_container_size = RESERVED_STORAGE_GB_PER_CORE * worker_cores
    if local_ssd_data_disk:
        return azure_local_ssd_size(worker_type, worker_cores) - reserved_image_size - reserved_container_size
    return external_data_disk_size_gib - reserved_image_size - reserved_container_size


def azure_requested_to_actual_storage_bytes(storage_bytes, allow_zero_storage):
    if storage_bytes > AZURE_MAX_PERSISTENT_SSD_SIZE_GIB * 1024 ** 3:
        return None
    if allow_zero_storage and storage_bytes == 0:
        return storage_bytes
    # actual minimum storage size is 4 Gi on Azure, but keeping 10 to be consistent with gcp
    return max(10 * 1024**3, storage_bytes)


def azure_is_valid_storage_request(storage_in_gib: int) -> bool:
    return 10 <= storage_in_gib <= AZURE_MAX_PERSISTENT_SSD_SIZE_GIB
