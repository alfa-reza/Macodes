# Hosts, Folders, Tags & VPS Inventory

> **Summary:** Organization, filtering, opt-in SSH server inventory probing, and manual cloud provider labels on Macodes.

## Hosts, Folders & Tags

- **Root vs. Folder Scope**: On the main Hosts screen, the `Folders` section shows root folders (`parentId == null`) as compact rows (showing host/subfolder counts or `Empty`), and `All Hosts` shows hosts across all folders with pinned hosts ordered first (without duplicating entries). Tapping into a folder scopes the view to its direct subfolders and direct hosts, with a breadcrumb bar above the list.
- **Folders as Containers**: Folders organize hosts visually and do not inherit credentials, ports, or connection settings. Deleting a non-empty folder prompts the user to either move child folders/hosts up to the parent folder (`deleteContents = false`) or delete the folder and its contents recursively (`deleteContents = true`).
- **User Tags (`HostTag`)**: Stored in `macodes.host_tags.v1` independently from system attributes (OS, vCPU, RAM, Cloud Provider). Removing a tag from a host preserves the tag definition; renaming or deleting a tag definition cascades across all hosts.

## Search & Filter Semantics

- **Local Search**: Matches host name, hostname/IP, username, port, folder breadcrumb names (including nested folders), tags, detected OS, and cloud provider labels without opening an SSH connection.
- **Filter Sheet Rules**:
  - Multi-tag selection uses **AND** (a host must match all selected tags).
  - Multi-select within **Protocol** (`SSH`, `Mosh`) and **Cloud Provider** uses **OR**.
  - Categories (**Tags**, **Protocol**, **Cloud Provider**, **Pinned Only**) combine using **AND**.
  - Active filter chips are shown below the toolbar only when filters are active and can be dismissed individually or via **Clear all**.

## VPS Inventory Detection (Opt-In)

- **Behavior**: Disabled by default (`Host.autoDetectInventory = false`). When enabled, Macodes runs a single read-only, rootless POSIX command (`INVENTORY_PROBE_COMMAND` in `VpsInventory.kt`) in a background coroutine after SSH authentication succeeds (or via manual **Refresh specs** on Host Details).
- **Detected Fields**: Logical vCPU count, total RAM, root disk capacity, CPU architecture, and detection timestamp cached locally on `Host.inventory`.
- **Limitations**: Failures or command timeouts (5s on active sessions, 8s on one-shot refresh) never fail or block terminal sessions. Containers/restricted shells lacking `nproc`/`/proc`/`df`/`sysctl` may yield partial specs or `Not detected`.

## Cloud Provider Labels & Migration

- **Optional Manual Labels**: `Host.cloudProvider` (`AWS`, `OCI`, `GCP`, `DIGITALOCEAN`, `AZURE`, `OTHER`, or `null` for Not Specified) is strictly manual; Macodes never probes cloud metadata endpoints (`169.254.169.254`) or syncs cloud APIs in this branch.
- **Schema Compatibility**: `Host` fields (`isPinned`, `cloudProvider`, `autoDetectInventory`, `inventory`) carry defaults and deserialize cleanly with `Json { ignoreUnknownKeys = true }` for all existing stored host records.
