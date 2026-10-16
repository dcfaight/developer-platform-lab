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

`kubernetes/` is reserved for the approved sample workload, quotas, probes,
ownership labels, and RBAC. No provisioning manifests are implemented yet.
Do not commit kubeconfig files or secrets. Use only this dedicated local cluster.
