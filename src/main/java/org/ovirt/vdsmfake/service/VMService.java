/**
 Copyright (c) 2012 Red Hat, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

           http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
package org.ovirt.vdsmfake.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Collection;

import org.ovirt.vdsmfake.domain.Device;
import org.ovirt.vdsmfake.domain.Host;
import org.ovirt.vdsmfake.domain.VM;
import org.ovirt.vdsmfake.task.TaskProcessor;
import org.ovirt.vdsmfake.task.TaskRequest;
import org.ovirt.vdsmfake.task.TaskType;

/**
 *
 *
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class VMService extends AbstractService {

    final static VMService instance = new VMService();

    public static VMService getInstance() {
        return instance;
    }

    public VMService() {
    }

    public Map list() {
        final Host host = getActiveHost();

        final Map resultMap = getDoneStatus();

        final List statusList = new ArrayList();
        for (VM vm : host.getRunningVMs().values()) {
            Map vmMap = map();
            vmMap.put("status", vm.getStatus().toString()); // Up
            vmMap.put("vmId", vm.getId()); // 4c36aca1-577f-4533-987d-a8288faab149
            statusList.add(vmMap);
        }

        resultMap.put("vmList", statusList);

        return resultMap;
    }
    private final static List fullListMapKeys = Arrays.asList("username fqdn acpiEnable emulatedMachine pid transparentHugePages keyboardLayout displayPort displaySecurePort timeOffset cpuType pauseCode nicModel smartcardEnable kvmEnable pitReinjection smp vmType displayIp clientIp smpCoresPerSocket nice".split(" "));
    public Map list(boolean fullStatus, List vmList) {
        final Host host = getActiveHost();

        final Map resultMap = getDoneStatus();

        final List statusList = new ArrayList();
        for (VM vm : host.getRunningVMs().values()) {
            if (!vmList.isEmpty() && !vmList.contains(vm.getId())) {
                continue;
            }

            Map vmMap = null;
            if (fullStatus) {
                vmMap = VMInfoService.getInstance().getFromKeys(vm, fullListMapKeys);
                vmMap.put("status", vm.getStatus().toString()); // Up
                vmMap.put("vmId", vm.getId()); // 4c36aca1-577f-4533-987d-a8288faab149
                Map customMap = vm.getCustomMap();
                if( customMap != null ) {
                    vmMap.put("custom", vm.getCustomMap());
                }
                vmMap.put("devices", vm.getDeviceList());
                vmMap.put("memSize", vm.getMemSize());
                vmMap.put("vmName", vm.getName()); // Fedora17_test1
                vmMap.put("display", vm.getDisplayType());
            }
            else {
                vmMap = map();
                vmMap.put("status", vm.getStatus().toString()); // Up
                vmMap.put("vmId", vm.getId()); // 4c36aca1-577f-4533-987d-a8288faab149
            }

            statusList.add(vmMap);
        }

        resultMap.put("vmList", statusList);

        return resultMap;
    }


    public Map migrate(Map request) {
        // String method = (String) request.get("method"); // online
        String dst = (String)request.get("dst"); // 10.34.63.178:54321
        // String src = (String)request.get("src"); // 10.34.63.177
        String vmId = (String)request.get("vmId"); // 79567083-9889-4bcc-90e3-291885b0da7f

        boolean success = true;

        final VM vm = getActiveHost().getRunningVMs().get(vmId);
        if (vm == null) {
            log.info("VM not found: " + vmId);
            throw new RuntimeException("VM not found: " + vmId);
        }

        // bind clone of VM to the target host
        VM targetVM = vm.clone();
        vm.setStatus(VM.VMStatus.MigratingFrom);
        targetVM.setStatus(VM.VMStatus.MigratingTo);

        String targetServerName = dst;

        // get target server
        int idx = dst.indexOf(':');
        if(idx != -1) {
            targetServerName = dst.substring(0, idx);
        }

        final Host targetHost = getHostByName(targetServerName);
        if (targetHost == null) {
            log.info("Target host not found: " + dst);
            throw new RuntimeException("Target host not found: " + dst + ", name: " + targetServerName);
        }
        targetVM.setHost(targetHost);
        targetHost.getRunningVMs().put(targetVM.getId(), targetVM);

        // add asynch task
        TaskProcessor.getInstance().addTask(new TaskRequest(TaskType.FINISH_MIGRATED_FROM_VM, 10000l, vm));
        TaskProcessor.getInstance().addTask(new TaskRequest(TaskType.FINISH_MIGRATED_TO_VM, 10000l, targetVM));
        // plan next status task
        TaskProcessor.getInstance().addTask(new TaskRequest(TaskType.FINISH_MIGRATED_FROM_VM_REMOVE_FROM_HOST, 20000l, vm));

        Map resultMap = map();

        Map statusMap = map();
        statusMap.put("message", success ? "Migration process starting" : "VM not found");
        statusMap.put("code", (success ? "0" : "100"));

        resultMap.put("status", statusMap);

        log.info("Migrating VM {} from host: {} to: {}", new Object[] { vm.getId(), vm.getHost().getName(), targetHost.getName() });

        return resultMap;
    }
    static private final List VmStatsKeys = Arrays.asList("username fqdn memUage balloonInfo username acpiEnable pid displayIp displayPort session displaySecurePort timeOffset hash pauseCode kvmEnable monitorResponse statsAge elapsedTime vmType cpuSys appsList guestIPs".split(" "));
    public Map getVmStats(String uuid) {
        final Host host = getActiveHost();

        Map resultMap = getDoneStatus();

        List statusList = new ArrayList();

        VM vm = host.getRunningVMs().get(uuid);

        if (vm != null) {
            Map vmStatMap = VMInfoService.getInstance().getFromKeys(vm, VmStatsKeys);
            vmStatMap.put("status", vm.getStatus().toString());
            vmStatMap.put("network", getNetworkStatsMap(vm));
            vmStatMap.put("vmId", vm.getId());
            vmStatMap.put("displayType", vm.getDisplayType());
            vmStatMap.put("disks", getVMDisksMap(vm));
            vmStatMap.put("elapsedTime", vm.getElapsedTimeInSeconds());
            statusList.add(vmStatMap);
        }

        resultMap.put("statsList", statusList);

        return resultMap;
    }

    Map getNetworkStatsMap(VM vm) {
        List<Device> nicDevices = vm.getDevicesByType(Device.DeviceType.NIC);

        String macAddress = vm.getMacAddress();
        if( macAddress.equals(VM.NONE_STRING) ) {
            return map();
        }

        Map resultMap = map();
        int count = 0;
        for(Device device : nicDevices)
        {
            Map netStats = map();

            netStats.put("txErrors", "0");
            netStats.put("state", "unknown");
            netStats.put("macAddr", device.getMacAddr()); // 00:1a:4a:16:01:51
            netStats.put("name", "vnet0");
            netStats.put("txDropped", "0");
            netStats.put("txRate", "0.0");
            netStats.put("rxErrors", "0");
            netStats.put("rxRate", "0.0");
            netStats.put("rxDropped", "0");

            resultMap.put("vnet" + Integer.valueOf(count), netStats);
            ++count;
        }
        return resultMap;
    }

    Map getVMDisksMap(VM vm) {
        Map resultMap = map();

        List<Device> diskDevices = vm.getDevicesByType(Device.DeviceType.DISK);

        List values = Arrays.asList("a b c d e f g h i j k l m n o p q r s t u v w x y z".split(" "));
        for(Device disk : diskDevices) {
            if( values.isEmpty() ) {
                break;
            }
            Map diskMap = map();
            diskMap.put("readLatency", "0");
            diskMap.put("apparentsize", "197120");
            diskMap.put("writeLatency", "0");
            diskMap.put("imageID", disk.getImageID());
            diskMap.put("flushLatency", "0");
            diskMap.put("readRate", "0");
            diskMap.put("truesize", "139264");
            diskMap.put("writeRate", "0.00");

            resultMap.put("vd" + values.get(0), diskMap);
            values.remove(0);
        }

        return resultMap;
    }

    private Collection<VM> getVmListFromIds(List vmIds) {

        final Host host = getActiveHost();

        if( vmIds == null || vmIds.isEmpty() ) {
            return host.getRunningVMs().values();
        }

        ArrayList<VM> vmList = new ArrayList<VM>();
        for( Object id : vmIds ) {
            if( host.getRunningVMs().containsKey(id) ) {
                vmList.add( host.getRunningVMs().get(id) );
            }
        }

        return vmList;
    }

    private Map extractKeysFromVmAndHash(VM vm, List keys, Map stats) {
        if( stats == null ) {
            stats = fillVmStatsMap(vm);
        }
        Map result = map();
        for( Object key : keys ) {
            if( stats.containsKey(key) ) {
                result.put(key, stats.get(key));
            }
        }
        result.put("hashes", getRuntimeStatsHashesForVm(vm, stats));
        return result;
    }

    private Map extractKeysFromVm(VM vm, List keys, Map stats) {
        if( stats == null ) {
            stats = fillVmStatsMap(vm);
        }
        Map result = map();
        for( Object key : keys ) {
            if( stats.containsKey(key) ) {
                result.put(key, stats.get(key));
            }
        }
        return result;
    }

    private Map getExtractedStatsAndHash(List keys, List vmIds, boolean hashes) {
        final Host host = getActiveHost();
        Map result = map();
        for (VM vm : getVmListFromIds(vmIds)) {
            Map stats = null;
            if( hashes ) {
                stats = extractKeysFromVmAndHash(vm, keys, fillVmStatsMap(vm));
            }
            else {
                stats = extractKeysFromVm(vm, keys, fillVmStatsMap(vm));
            }
            result.put(vm.getId(), stats);
        }
        return result;
    }

    private Map getExtractedStats(List keys, List vmIds) {
        return getExtractedStatsAndHash(keys, vmIds, false);
    }

    private Map getRuntimeStatsHashesForVm(VM vm, Map stats)
    {
        Map hashes = map();
        Object hash = "0";
        if( stats.containsKey("hash") ) {
            hash = stats.get("hash");
        }
        hashes.put("config", hash);
        hashes.put("info", "" + extractKeysFromVm(vm, VmConfInfoKeys, stats).hashCode());
        hashes.put("status", "" + extractKeysFromVm(vm, VmStatusKeys, stats).hashCode());
        hashes.put("guestDetails", "" + extractKeysFromVm(vm, VmGuestDetailsKeys, stats).hashCode());
        return hashes;
    }

    private final static List VmRuntimeStatsKeys = Arrays.asList("cpuSys cpuUser memUsage elapsedTime status statsAge".split(" "));
    public Map getAllVmRuntimeStats() {
        Map resultMap = getDoneStatus();
        resultMap.put("runtimeStats", getExtractedStatsAndHash(VmRuntimeStatsKeys, null, true));
        return resultMap;
    }

    private final static List VmDeviceStatsKeys = Arrays.asList("network disks disksUsage balloonInfo memoryStats".split(" "));
    public Map getAllVmDeviceStats() {
        Map resultMap = getDoneStatus();
        resultMap.put("deviceStats", getExtractedStats(VmDeviceStatsKeys, null));
        return resultMap;
    }

    private final static List VmStatusKeys = Arrays.asList("timeOffset monitorResponse clientIp lastLogin username session guestIps".split(" "));
    public Map getVmStatus(List vmIds) {
        Map resultMap = getDoneStatus();
        resultMap.put("vmStatus", getExtractedStats(VmStatusKeys, vmIds));
        return resultMap;
    }

    private final static List VmConfInfoKeys = Arrays.asList("acpiEnable vmType guestName guestOS kvmEnable pauseCode displayIp displayPort displaySecurePort pid".split(" "));
    public Map getVmConfInfo(List vmIds) {
        Map resultMap = getDoneStatus();
        resultMap.put("vmConfInfo", getExtractedStats(VmConfInfoKeys, vmIds));
        return resultMap;
    }

    private final static List VmGuestDetailsKeys = Arrays.asList("appsList netIfaces".split(" "));
    public Map getVmGuestDetails(List vmIds) {
        Map resultMap = getDoneStatus();
        resultMap.put("guestDetails", getExtractedStats(VmGuestDetailsKeys, vmIds));
        return resultMap;
    }

    private Map fillVmStatsMap(VM vm)
    {
        Map vmStatMap = VMInfoService.getInstance().getFromKeys(vm, VmStatsKeys);
        vmStatMap.put("status", vm.getStatus().toString());
        Map network = getNetworkStatsMap(vm);
        if( !network.isEmpty() ) {
            vmStatMap.put("network", network);
        }
        vmStatMap.put("vmId", vm.getId());
        Map disks = getVMDisksMap(vm);
        if( !disks.isEmpty() ) {
            vmStatMap.put("disks", disks);
        }
        vmStatMap.put("elapsedTime", vm.getElapsedTimeInSeconds());
        return vmStatMap;
    }

    public Map getAllVmStats() {
        final Host host = getActiveHost();

        Map resultMap = getDoneStatus();

        // iterate vms

        List statusList = new ArrayList();

        for (VM vm : host.getRunningVMs().values()) {
            statusList.add(fillVmStatsMap(vm));
        }

        resultMap.put("statsList", statusList);

        return resultMap;
    }

    public Map setVmTicket(String uuid, String password, String ttl, String existingConnAction, Map params) {
        return getDoneStatus();
    }

    public Map destroy(String vmId) {
        final VM vm = getActiveHost().getRunningVMs().get(vmId);
        if (vm == null) {
            log.info("VM not found: " + vmId);
            throw new RuntimeException("VM not found: " + vmId);
        }

        vm.setStatus(VM.VMStatus.PoweringDown);

        Map resultMap = map();

        // add async task
        TaskProcessor.getInstance().addTask(new TaskRequest(TaskType.SHUTDOWN_VM, 5000l, vm));

        Map statusMap = map();
        statusMap.put("message", "Machine destroyed");
        statusMap.put("code", "0");

        resultMap.put("status", statusMap);

        return resultMap;
    }

    public Map shutdown(String vmId, String timeout, String message) {
        final Map resultMap = getStatusMap("Machine shut down", 0);

        final VM vm = getActiveHost().getRunningVMs().get(vmId);
        if (vm != null) {
            vm.setStatus(VM.VMStatus.PoweringDown);
        }

        // add asynch task
        TaskProcessor.getInstance().addTask(new TaskRequest(TaskType.SHUTDOWN_VM, 5000l, vm));

        return resultMap;
    }

    public Map create(Map vmParams) {
        try {
            final Host host = getActiveHost();

            final String vmId = (String) vmParams.get("vmId");

            final VM vm = new VM();
            vm.setTimeCreated(System.currentTimeMillis());
            vm.setId(vmId);
            vm.setName((String) vmParams.get("vmName"));
            vm.setCpuType((String) vmParams.get("cpuType"));
            vm.setHost(host);

            Integer memSize = 0;
            Object boxedMemSize = vmParams.get("memSize");
            if(boxedMemSize instanceof String) {
                memSize = Integer.parseInt((String) boxedMemSize);
            }
            else {
                memSize = (Integer)boxedMemSize;
            }
            vm.setMemSize(memSize);

            final Object[] devices = (Object[]) vmParams.get("devices");
            vm.setDeviceList(devices == null ? new ArrayList() : Arrays.asList(devices));
            Map custom = (Map) vmParams.get("custom");
            vm.setCustomMap(custom != null ? custom : map());

            // append address tag when missing by the device
            vm.generateDevicesAddressIfMissing();

            // convert Maps to important Device objects
            vm.parseDevices();

            host.getRunningVMs().put(vm.getId(), vm);
            // persist
            updateHost(host);

            // add asynch tasks
            TaskProcessor.getInstance().addTask(new TaskRequest(TaskType.START_VM, 2000l, vm));
            // plan next status task
            TaskProcessor.getInstance().addTask(new TaskRequest(TaskType.START_VM_AS_UP, 10000l, vm));

            final Map resultMap = getDoneStatus();

            vmParams.put("status", vm.getStatus().toString()); // WaitForLaunch
            resultMap.put("vmList", vmParams);

            log.info("VM {} created on host {}", vmId, host.getName());

            return resultMap;
        } catch (Exception e) {
            log.error(ERROR, e);
            throw new RuntimeException(ERROR, e);
        }
    }
}
