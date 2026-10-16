# Sample application

The provisioning slice uses a fixed `nginxinc/nginx-unprivileged:1.28-alpine`
sample Deployment built by the backend Kubernetes package. It listens on port
8080, has HTTP readiness/liveness probes and resource requests/limits, and does
not accept caller-supplied images or manifests.

This directory remains reserved for a future custom sample application and its
container build. No custom application, Service, public ingress, or routing is
implemented. For local inspection, use the dedicated cluster context:

```sh
kubectl --context kind-developer-platform-lab -n "$NAMESPACE" get deployments,pods
kubectl --context kind-developer-platform-lab -n "$NAMESPACE" port-forward deployment/sample-service 8081:8080
```

The worker reports readiness only after observing the Deployment's current
generation and ready/available replicas. This is initial provisioning health,
not continuous monitoring or a production availability guarantee.
