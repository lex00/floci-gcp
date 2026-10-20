# Cloud Asset Inventory

floci-gcp serves the Cloud Asset Inventory v1 `searchAllResources` method over REST JSON. It
searches the resources the emulator holds in a project and joins each one with the Resource
Manager [tags](resource-manager.md#tags) bound to it.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_CLOUDASSET_ENABLED` | `true` | Enable/disable the Cloud Asset API |
| `FLOCI_GCP_SERVICES_CLOUDASSET_LAG_SECONDS` | `0` | Seconds a new, changed or deleted resource keeps its previous search answer |

## Endpoint

`GET /v1/{scope}:searchAllResources` shares its path with the IAM API, so it mounts under a
`/cloudasset` prefix and the routing filter maps clients onto it the same way it does for
[GKE](gke.md):

- **Path mode:** call `/cloudasset/v1/projects/{project}:searchAllResources`, or give the client an
  endpoint of `http://localhost:4588/cloudasset`. The Java client and Go clients that keep the path
  of a custom endpoint work this way.
- **Host mode:** point the client at `http://cloudasset.localhost:4588`, and `/v1/...` is rewritten to
  `/cloudasset/v1/...`.

`scope` is `projects/{projectId}` or `projects/{projectNumber}`. Folder and organization scopes answer
`501 UNIMPLEMENTED`.

## Searched resources

| Asset type | Name |
|---|---|
| `storage.googleapis.com/Bucket` | `//storage.googleapis.com/{bucket}` |
| `pubsub.googleapis.com/Topic` | `//pubsub.googleapis.com/projects/{project}/topics/{topic}` |
| `pubsub.googleapis.com/Subscription` | `//pubsub.googleapis.com/projects/{project}/subscriptions/{subscription}` |
| `compute.googleapis.com/Instance`, `Disk` | `//compute.googleapis.com/projects/{project}/zones/{zone}/{instances or disks}/{name}` |
| `compute.googleapis.com/Address`, `Subnetwork` | `//compute.googleapis.com/projects/{project}/regions/{region}/{addresses or subnetworks}/{name}` |
| `compute.googleapis.com/GlobalAddress`, `Network`, `Firewall` | `//compute.googleapis.com/projects/{project}/global/{addresses, networks or firewalls}/{name}` |
| `iam.googleapis.com/ServiceAccount` | `//iam.googleapis.com/projects/{project}/serviceAccounts/{uniqueId}` |

Each result carries `name`, `assetType`, `project` (`projects/{projectNumber}`), `displayName`,
`location`, `labels`, `tags`, `tagKeys`, `tagValues`, `tagValueIds`, `createTime` and `updateTime`
where the resource has them. The tag fields list the tags bound directly to the resource; a binding
on a Compute resource by numeric ID counts as well. `effectiveTags` is not served.

## Query, paging and masks

`query` accepts these statements, joined with `AND`:

| Statement | Matches |
|---|---|
| `labels.KEY=VALUE` | The label has exactly that value |
| `labels.KEY:VALUE` | The label has that value, or a value starting with it when `VALUE` ends in `*` |
| `labels.KEY:*` | The label is present |
| `tagKeys`, `tagValues`, `tagValueIds` with `=` | A bound tag has exactly that name |
| `tagKeys`, `tagValues`, `tagValueIds` with `:` | A bound tag's name, a trailing run of its segments (`env/prod`), or a prefix with `*` |

Values may be quoted. Any other statement fails with `400 INVALID_ARGUMENT` instead of being matched
loosely. `assetTypes` takes regular expressions, `orderBy` sorts by `name`, `assetType`, `project`,
`displayName`, `location`, `createTime` or `updateTime` with `ASC` or `DESC`, and `readMask` returns
only the fields it names.

`pageSize` defaults to 100 and is capped at 500. The last page has no `nextPageToken`. A page token
works once, and only with the parameters that produced it; a reused or unknown token fails with
`400 INVALID_ARGUMENT`. A search with no results answers `{}`.

## Lag

Search in GCP is eventually consistent. With `FLOCI_GCP_SERVICES_CLOUDASSET_LAG_SECONDS` above zero,
a new resource is missing, a changed one keeps its old answer, and a deleted one stays listed until
the lag has passed. The lag counts from the first search that sees the change, so a resource that
existed before the first search is held for the lag too.

## Service Usage and IAM

With Service Usage enforcement on, a project that has not enabled `cloudasset.googleapis.com` gets
`403 PERMISSION_DENIED` with an `ErrorInfo` whose reason is `SERVICE_DISABLED`.

With IAM enforcement on, a search needs `cloudasset.assets.searchAllResources` and
`serviceusage.services.use` on the project, as the
[access control reference](https://cloud.google.com/asset-inventory/docs/access-control) lists.
`roles/cloudasset.viewer` and `roles/cloudasset.owner` grant the first,
`roles/serviceusage.serviceUsageConsumer` grants the second, Owner and Editor grant both, and Viewer
grants only the first.
