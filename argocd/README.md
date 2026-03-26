# ArgoCD Setup for AdTech Demo

This directory contains ArgoCD configuration for managing the RTB Ingress Benchmark application using GitOps principles.

## Prerequisites

- kind cluster running (`adtech-demo`)
- kubectl configured to access the cluster
- Git repository with your Helm charts

## Installation

### 1. Install ArgoCD

```bash
# Make install script executable
chmod +x argocd/install-argocd.sh

# Install ArgoCD
./argocd/install-argocd.sh
```

This will:
- Create `argocd` namespace
- Install ArgoCD components
- Configure NodePort access on port 30443
- Display the initial admin password

### 2. Access ArgoCD UI

```bash
# ArgoCD UI
open https://localhost:30443

# Username: admin
# Password: (displayed after installation)
```

**Note**: Your browser will warn about the self-signed certificate. This is expected for local development.

### 3. Install ArgoCD CLI (Optional)

```bash
# macOS
brew install argocd

# Login via CLI
argocd login localhost:30443 --insecure --username admin
```

## Deploying AdTech Demo with ArgoCD

### Option 1: Using Git Repository (Recommended - True GitOps)

First, ensure your Helm chart is pushed to Git:

```bash
git add helm/ argocd/
git commit -m "Add Helm chart and ArgoCD configuration"
git push origin dev
```

Then apply the ArgoCD Application:

```bash
# Apply the Application manifest
kubectl apply -f argocd/application.yaml

# Watch the sync progress
kubectl get application -n argocd -w
```

### Option 2: Using ArgoCD UI

1. Open ArgoCD UI: https://localhost:30443
2. Click **"+ NEW APP"**
3. Fill in:
   - **Application Name**: `adtech-demo`
   - **Project**: `default`
   - **Sync Policy**: `Automatic`
   - **Repository URL**: `https://github.com/dtkmn/rtb-ingress-benchmark.git`
   - **Revision**: `dev` (or `HEAD`)
   - **Path**: `helm/rtb-ingress-benchmark`
   - **Cluster**: `https://kubernetes.default.svc`
   - **Namespace**: `adtech-demo`
4. Click **"CREATE"**

### Option 3: Using ArgoCD CLI

```bash
argocd app create adtech-demo \
  --repo https://github.com/dtkmn/rtb-ingress-benchmark.git \
  --path helm/rtb-ingress-benchmark \
  --dest-server https://kubernetes.default.svc \
  --dest-namespace adtech-demo \
  --revision dev \
  --sync-policy automated \
  --auto-prune \
  --self-heal
```

## Managing the Application

### Sync Application Manually

```bash
# Via CLI
argocd app sync adtech-demo

# Via kubectl
kubectl patch application adtech-demo -n argocd --type merge -p '{"operation":{"initiatedBy":{"username":"admin"},"sync":{}}}'
```

### View Application Status

```bash
# Via CLI
argocd app get adtech-demo

# Via kubectl
kubectl get application adtech-demo -n argocd -o yaml
```

### View Application Logs

```bash
argocd app logs adtech-demo --follow
```

### Update Configuration

To update the application:

1. Modify `helm/rtb-ingress-benchmark/values.yaml`
2. Commit and push changes
3. ArgoCD will automatically detect and sync (if auto-sync is enabled)

```bash
# Manual sync if needed
argocd app sync adtech-demo
```

### Rollback

```bash
# List revisions
argocd app history adtech-demo

# Rollback to specific revision
argocd app rollback adtech-demo <REVISION>
```

## Configuration Options

### Disable Auto-Sync

Edit `argocd/application.yaml` and remove the `automated` section:

```yaml
syncPolicy:
  # automated:  # Remove this
  #   prune: true
  #   selfHeal: true
  syncOptions:
    - CreateNamespace=true
```

### Adjust Sync Wave (Deployment Order)

Add annotations to Helm templates to control deployment order:

```yaml
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "1"  # Deploy in wave 1
```

Lower numbers deploy first:
- Wave 0: Infrastructure (Zookeeper, Kafka, PostgreSQL)
- Wave 1: Application services
- Wave 2: Monitoring (Prometheus, Grafana)

### Add Health Checks

ArgoCD automatically checks health for standard Kubernetes resources. For custom resources:

```yaml
# In application.yaml
spec:
  health:
    - kind: MyCustomResource
      check: |
        hs = {}
        if obj.status ~= nil then
          if obj.status.phase == "Running" then
            hs.status = "Healthy"
            hs.message = "Running"
            return hs
          end
        end
        hs.status = "Progressing"
        hs.message = "Waiting"
        return hs
```

## Monitoring

### View in ArgoCD UI

The UI provides:
- Application health status
- Resource tree visualization
- Sync status and history
- Real-time logs
- Diff viewer (Git vs Cluster)

### Prometheus Metrics

ArgoCD exports metrics to Prometheus:

```yaml
# Add to prometheus.yml
- job_name: 'argocd'
  static_configs:
    - targets: ['argocd-metrics:8082']
```

### Notifications

Configure notifications for sync failures:

```bash
# Install argocd-notifications
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj-labs/argocd-notifications/stable/manifests/install.yaml
```

## Troubleshooting

### Application Stuck in Syncing

```bash
# Check sync status
argocd app get adtech-demo

# View sync errors
kubectl describe application adtech-demo -n argocd

# Force refresh
argocd app get adtech-demo --refresh
```

### Out of Sync Issues

```bash
# Show differences
argocd app diff adtech-demo

# Force sync
argocd app sync adtech-demo --force
```

### Resource Hooks Failed

```bash
# View hook logs
kubectl logs -n adtech-demo -l app.kubernetes.io/instance=adtech-demo,argocd.argoproj.io/hook-type

# Delete failed hook
kubectl delete job <hook-job-name> -n adtech-demo
```

## Migration from Helm

If you've already deployed using `helm install`:

```bash
# Delete Helm release (but keep resources)
helm uninstall adtech-demo --keep-history

# Let ArgoCD adopt the resources
kubectl apply -f argocd/application.yaml

# ArgoCD will now manage these resources
```

## Best Practices

1. **Always use Git as source of truth**: Commit Helm charts to Git
2. **Enable auto-sync with caution**: Use for dev environments, manual for production
3. **Use sync waves**: Control deployment order for dependencies
4. **Implement health checks**: Ensure proper resource validation
5. **Configure notifications**: Get alerted on sync failures
6. **Use ArgoCD projects**: Isolate applications by team/environment
7. **Leverage Helm hooks**: For pre/post deployment tasks

## Cleanup

### Delete Application (Keep Resources)

```bash
argocd app delete adtech-demo --cascade=false
```

### Delete Application (Delete Resources)

```bash
argocd app delete adtech-demo
# or
kubectl delete application adtech-demo -n argocd
```

### Uninstall ArgoCD

```bash
kubectl delete namespace argocd
```

## Additional Resources

- [ArgoCD Documentation](https://argo-cd.readthedocs.io/)
- [ArgoCD Best Practices](https://argo-cd.readthedocs.io/en/stable/user-guide/best_practices/)
- [Helm with ArgoCD](https://argo-cd.readthedocs.io/en/stable/user-guide/helm/)
