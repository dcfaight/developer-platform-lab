# Infrastructure

`kind/cluster.yaml` defines a dedicated, single-node local development cluster.
With Docker and kind installed, from the repository root:

```sh
kind create cluster --name developer-platform-lab --config infra/kind/cluster.yaml
kubectl --context kind-developer-platform-lab get nodes
```

Remove the local cluster when finished:

```sh
kind delete cluster --name developer-platform-lab
```

The backend worker builds the approved namespace and sample Deployment manifest
in its Kubernetes package. `kubernetes/` remains reserved for future standalone
manifests, quotas, and RBAC. There is no arbitrary manifest or image input.

Enable the worker as described in [backend/README.md](../backend/README.md).
It requires the `kind-developer-platform-lab` kubeconfig context and a loopback
HTTPS API server; remote clusters are deliberately unsupported. Use a host-run
backend, not a container with a different loopback network. Do not commit
kubeconfig files or secrets. The kind admin credentials are for this disposable
lab only, not a production permission model.

Provisioned namespaces are retained, including after failures or TTL expiry.
There is no deletion/cleanup API in this slice. Deleting the entire disposable
kind cluster above is a manual lab reset, not platform cleanup.
