# Compute Engine

Compute Engine uses its native REST v1 paths under `/compute/v1/projects/{project}`
on port 4588. Configure SDK endpoints explicitly and use synthetic credentials.
The emulator does not execute virtual machines or forward network traffic.

Set `FLOCI_GCP_SERVICES_COMPUTE_ENABLED=false` to disable the service.
`FLOCI_GCP_SERVICES_COMPUTE_OPERATION_DELAY_MS` defaults to 50 ms.
Regions and zones come from the bundled [location catalog](locations.md): all 43 regions and
their 130 real zones (for example `us-central1-f` and `europe-west1-d`). To serve fewer regions,
set `FLOCI_GCP_SERVICES_COMPUTE_REGIONS` to a comma-separated list, such as
`us-central1,europe-west1`. Each listed region keeps its real zones from the catalog. Unset or
empty serves the whole catalog. Any region or zone outside that set returns `404`.
Machine, accelerator and disk catalogs are a small deterministic fixture catalog,
not an assertion of current Google availability, quotas or pricing. The machine
catalog includes the E2 shared-core types `e2-micro`, `e2-small` and `e2-medium`.

The Terraform and OpenTofu `google` provider reaches the service with
`compute_custom_endpoint = "http://localhost:4588/compute/v1/"`.

Resources and operations share a project-scoped checkpoint through StorageFactory.
Normal writes are validated against a private snapshot before committing. Operation
completion is advanced when the project is read, using persisted timestamps, so
polling and restart recovery do not depend on a request-scoped background thread.
Resource metadata changes are committed when the operation is accepted; lifecycle
status becomes final at operation completion. No guest progress is measured.

## Networking and operations

Custom-mode networks, regional subnetworks, firewall rules, and global/regional
IPv4 addresses support insert/get/list/delete. Firewalls also support updates.
Subnet overlap, scope, references, firewall ports and dependent-resource deletion
are validated. External addresses come from the documentation-only 203.0.113.0/24
range. Auto-mode VPCs, IPv6, Shared VPC and cross-project references are unsupported.
Networks, subnetworks, firewalls and the load balancing resources have no `labels` or
`labelFingerprint` field in Compute v1, so a request body carrying either is refused with
`400 INVALID_ARGUMENT`.

Global, regional and zonal operations support get/list/wait/delete. A wait may
return an unfinished operation, which clients must continue polling. Nonzero UUID
request IDs deduplicate mutations. Operation responses use Compute's `status`,
`targetLink` and scope fields, not `google.longrunning.Operation`.

Inventory supports `maxResults`, opaque `pageToken`, name ordering and descending
creation time. Filters support equality/inequality/existence on name, id, status,
family and labels, combined with AND. Other expressions fail explicitly. Tokens
are bound to project, collection, scope, filters and ordering. Inventory is not a
snapshot across concurrent writes.

Public interfaces follow the [Compute REST reference](https://docs.cloud.google.com/compute/docs/reference/rest/v1).
Credential acceptance retains the emulator's existing auth bypass; resource
isolation is not IAM enforcement.

## IAM enforcement

With `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce`, requests carrying a Floci-issued
service-account token are evaluated against the project's allow policy. Networks,
subnetworks, firewalls, regional and global addresses, instances, disks, the region,
zone and type catalogs, and zone, region and global operations are covered. Each
method checks the permission its Compute v1 REST reference lists, plus the field-level
permissions for the fields the emulator resolves: `network` and `subnetwork`
references, `sourceImage`, `sourceSnapshot` and `sourceDisk`, and `labels`, `metadata`,
`tags`, `serviceAccounts` and `deletionProtection` on create. An internal address
checks `createInternal`.

Policies bind at project level and conditions see the resource name, such as
`projects/p/global/networks/n`, and a type such as `compute.googleapis.com/Network`.
Compute resources hold no policies of their own. The predefined roles
`roles/compute.viewer`, `networkViewer`, `networkUser`, `networkAdmin`,
`securityAdmin`, `instanceAdmin.v1`, `storageAdmin` and `admin` grant the checked
permissions their published definitions include.

Images, snapshots and the load balancing resources have no mapping, so an evaluated
caller gets `FAILED_PRECONDITION` for them. Routes and routers are not emulated.
Anonymous callers and external credentials bypass evaluation.

## Instances and disks

Zonal instances support insert/get/list/aggregatedList/start/stop/reset/delete,
setMachineType, setMetadata, setTags, setLabels, addAccessConfig/deleteAccessConfig,
attachDisk/detachDisk and setDiskAutoDelete. A stopped VM has status TERMINATED;
reset does not imply guest health. One IPv4 NIC and one boot disk are required.
Machine changes require a stopped VM. Ephemeral external addresses are released
on stop; reserved addresses remain associated.

Zonal disks support insert/get/list/aggregatedList/delete/resize/update/setLabels.
Attached disks cannot be deleted. Deleting an instance retains disks unless their
attachment enables autoDelete. Disk sizes and source sizes are validated. Only
supported Hyperdisk types accept provisioned IOPS/throughput; unsupported knobs
fail. Performance limits are basic API checks, not complete machine/disk quota
or size-ratio emulation. Multi-writer disks and regional disks are unsupported.

## Images and snapshots

Global images support sources from disks, images and snapshots, family lookup,
labels, inventory and deletion. Global standard snapshots support disk sources,
labels, inventory and deletion. Source identity and size survive source deletion;
restored disks cannot be smaller than their source. Images do not acquire invented
backing snapshots. Copying a snapshot means restoring a disk and snapshotting it.
Guest disk bytes, regional/instant snapshots and public OS image catalogs are not
emulated.

## Global external application load balancer

Zonal GCE_VM_IP_PORT endpoint groups support endpoint attach/detach/list. Global
HTTP health checks, EXTERNAL_MANAGED backend services, URL maps, HTTP target proxies
and TCP forwarding rules support configuration and ordered cleanup. Backend and
URL-map updates require current fingerprints. Path matchers, host rules, pathRules
and session-affinity configuration are validated; actual request routing, health
checks, TLS certificates, regional load balancing and advanced routeRules are not
implemented. Endpoint membership is stored separately from the public NEG resource.

### Address ownership and release

Global external HTTP forwarding rules can share a PREMIUM IPv4 address when
their TCP ports do not overlap. Ownership uses complete resource links, and
deleting one rule preserves every other owner. A forwarding rule's IP address
is immutable; changing it requires replacing the rule.

Deleting an in-use address reservation through the API releases the reservation,
not the IP attached to its instance or forwarding rule. The attached address
remains unavailable to the allocator until the consumer releases it. This follows
[Google's API release semantics](https://docs.cloud.google.com/vpc/docs/reserve-static-external-ip-address#release_ip),
which differ from the Cloud console's restriction on releasing in-use addresses.
