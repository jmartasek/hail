from typing import Optional
import json
import base64
import aiohttp
import datetime
import logging
import secrets
import humanize

from hailtop.utils import time_msecs, time_msecs_str, retry_transient_errors
from hailtop import httpx
from gear import Database

from ..database import check_call_procedure
from ..globals import INSTANCE_VERSION
from ..instance_config import InstanceConfig
from ..cloud.utils import instance_config_from_config_dict

log = logging.getLogger('instance')


class Instance:
    @staticmethod
    def from_record(app, inst_coll, record):
        return Instance(
            app,
            inst_coll,
            record['name'],
            record['state'],
            record['cores_mcpu'],
            record['free_cores_mcpu'],
            record['time_created'],
            record['failed_request_count'],
            record['last_updated'],
            record['ip_address'],
            record['version'],
            record['location'],
            record['machine_type'],
            record['preemptible'],
            instance_config_from_config_dict(
                json.loads(base64.b64decode(record['instance_config']).decode())
            )
        )

    @staticmethod
    async def create(app,
                     inst_coll,
                     name: str,
                     activation_token,
                     cores: int,
                     location: str,
                     machine_type: str,
                     preemptible: bool,
                     instance_config: InstanceConfig,
                     ) -> 'Instance':
        db: Database = app['db']

        state = 'pending'
        now = time_msecs()
        token = secrets.token_urlsafe(32)

        cores_mcpu = cores * 1000

        await db.just_execute(
            '''
INSERT INTO instances (name, state, activation_token, token, cores_mcpu, free_cores_mcpu,
  time_created, last_updated, version, location, inst_coll, machine_type, preemptible, instance_config)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s);
''',
            (
                name,
                state,
                activation_token,
                token,
                cores_mcpu,
                cores_mcpu,
                now,
                now,
                INSTANCE_VERSION,
                location,
                inst_coll.name,
                machine_type,
                preemptible,
                base64.b64encode(json.dumps(instance_config.to_dict()).encode()).decode(),
            ),
        )
        return Instance(
            app,
            inst_coll,
            name,
            state,
            cores_mcpu,
            cores_mcpu,
            now,
            0,
            now,
            None,
            INSTANCE_VERSION,
            location,
            machine_type,
            preemptible,
            instance_config,
        )

    def __init__(
        self,
        app,
        inst_coll,
        name,
        state,
        cores_mcpu,
        free_cores_mcpu,
        time_created,
        failed_request_count,
        last_updated: int,
        ip_address,
        version,
        location: str,
        machine_type: str,
        preemptible: bool,
        instance_config: InstanceConfig,
    ):
        self.db: Database = app['db']
        self.client_session: httpx.ClientSession = app['client_session']
        self.inst_coll = inst_coll
        # pending, active, inactive, deleted
        self._state = state
        self.name = name
        self.cores_mcpu = cores_mcpu
        self._free_cores_mcpu = free_cores_mcpu
        self.time_created = time_created
        self._failed_request_count = failed_request_count
        self._last_updated = last_updated
        self.ip_address = ip_address
        self.version = version
        self.location = location
        self.machine_type = machine_type
        self.preemptible = preemptible
        self.instance_config = instance_config

    @property
    def state(self):
        return self._state

    async def activate(self, ip_address, timestamp):
        assert self._state == 'pending'

        rv = await check_call_procedure(
            self.db, 'CALL activate_instance(%s, %s, %s);', (self.name, ip_address, timestamp)
        )

        self.inst_coll.adjust_for_remove_instance(self)
        self._state = 'active'
        self.ip_address = ip_address
        self.inst_coll.adjust_for_add_instance(self)
        self.inst_coll.scheduler_state_changed.set()

        return rv['token']

    async def deactivate(self, reason: str, timestamp: Optional[int] = None):
        if self._state in ('inactive', 'deleted'):
            return

        if not timestamp:
            timestamp = time_msecs()

        rv = await self.db.execute_and_fetchone('CALL deactivate_instance(%s, %s, %s);', (self.name, reason, timestamp))

        if rv['rc'] == 1:
            log.info(f'{self} with in-memory state {self._state} was already deactivated; {rv}')
            assert rv['cur_state'] in ('inactive', 'deleted')

        self.inst_coll.adjust_for_remove_instance(self)
        self._state = 'inactive'
        self._free_cores_mcpu = self.cores_mcpu
        self.inst_coll.adjust_for_add_instance(self)

        # there might be jobs to reschedule
        self.inst_coll.scheduler_state_changed.set()

    async def kill(self):
        async def make_request():
            if self._state in ('inactive', 'deleted'):
                return
            try:
                await self.client_session.post(
                    f'http://{self.ip_address}:5000/api/v1alpha/kill',
                    timeout=aiohttp.ClientTimeout(total=30))
            except aiohttp.ClientResponseError as err:
                if err.status == 403:
                    log.info(f'cannot kill {self} -- does not exist at {self.ip_address}')
                    return
                raise

        await retry_transient_errors(make_request)

    async def mark_deleted(self, reason, timestamp):
        if self._state == 'deleted':
            return
        if self._state != 'inactive':
            await self.deactivate(reason, timestamp)

        rv = await self.db.execute_and_fetchone('CALL mark_instance_deleted(%s);', (self.name,))

        if rv['rc'] == 1:
            log.info(f'{self} with in-memory state {self._state} could not be marked deleted; {rv}')
            assert rv['cur_state'] == 'deleted'

        self.inst_coll.adjust_for_remove_instance(self)
        self._state = 'deleted'
        self.inst_coll.adjust_for_add_instance(self)

    @property
    def free_cores_mcpu(self):
        return self._free_cores_mcpu

    def adjust_free_cores_in_memory(self, delta_mcpu):
        self.inst_coll.adjust_for_remove_instance(self)
        self._free_cores_mcpu += delta_mcpu
        self.inst_coll.adjust_for_add_instance(self)

    @property
    def failed_request_count(self):
        return self._failed_request_count

    async def check_is_active_and_healthy(self):
        if self._state == 'active' and self.ip_address:
            try:
                async with self.client_session.get(f'http://{self.ip_address}:5000/healthcheck') as resp:
                    actual_name = (await resp.json()).get('name')
                    if actual_name and actual_name != self.name:
                        return False
                await self.mark_healthy()
                return True
            except Exception:
                log.exception(f'while requesting {self} /healthcheck')
                await self.incr_failed_request_count()
        return False

    async def mark_healthy(self):
        if self._state != 'active':
            return

        now = time_msecs()
        changed = (self._failed_request_count > 1) or (now - self._last_updated) > 5000
        if not changed:
            return

        await self.db.execute_update(
            '''
UPDATE instances
SET last_updated = %s,
  failed_request_count = 0
WHERE name = %s;
''',
            (now, self.name),
        )

        self.inst_coll.adjust_for_remove_instance(self)
        self._failed_request_count = 0
        self._last_updated = now
        self.inst_coll.adjust_for_add_instance(self)

    async def incr_failed_request_count(self):
        await self.db.execute_update(
            '''
UPDATE instances
SET failed_request_count = failed_request_count + 1 WHERE name = %s;
''',
            (self.name,),
        )

        self.inst_coll.adjust_for_remove_instance(self)
        self._failed_request_count += 1
        self.inst_coll.adjust_for_add_instance(self)

    @property
    def last_updated(self):
        return self._last_updated

    async def update_timestamp(self):
        now = time_msecs()
        await self.db.execute_update('UPDATE instances SET last_updated = %s WHERE name = %s;', (now, self.name))

        self.inst_coll.adjust_for_remove_instance(self)
        self._last_updated = now
        self.inst_coll.adjust_for_add_instance(self)

    def time_created_str(self):
        return time_msecs_str(self.time_created)

    def last_updated_str(self):
        return humanize.naturaldelta(datetime.timedelta(milliseconds=(time_msecs() - self.last_updated)))

    def __str__(self):
        return f'instance {self.name}'
