# Kubernetes Deployment with Helm

This guide will help you deploy the RTB Ingress Benchmark harness to a local Kubernetes cluster using kind (Kubernetes in Docker) and Helm.

## Prerequisites

Before deploying, ensure you have the following tools installed:

1. **Docker** - Container runtime
   ```bash
   docker --version
   ```

2. **kind** - Kubernetes in Docker
   ```bash
   # macOS
   brew install kind
   
   # Linux
   curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.20.0/kind-linux-amd64
   chmod +x ./kind
   sudo mv ./kind /usr/local/bin/kind
   ```

3. **kubectl** - Kubernetes CLI
   ```bash
   # macOS
   brew install kubectl
   
   # Linux
   curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
   chmod +x kubectl
   sudo mv kubectl /usr/local/bin/
   ```

4. **Helm** - Kubernetes package manager
   ```bash
   # macOS
   brew install helm
   
   # Linux
   curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
   ```

## Step 1: Build Docker Images

First, build all the Docker images locally:

```bash
# Build all images using docker-compose
docker-compose build

# Verify images are built
docker images | grep -E "quarkus-receiver|go-receiver|rust-receiver|quarkus-sinker"
```

Expected output:
```
quarkus-receiver         latest    ...    387MB
quarkus-receiver-native  latest    ...    271MB
go-receiver              latest    ...    51.6MB
rust-receiver            latest    ...    132MB
quarkus-sinker           latest    ...    582MB
```

## Step 2: Create a kind Cluster

Create a local Kubernetes cluster with port mappings for accessing services:

```bash
# Create the cluster using the configuration file
kind create cluster --config kind-config.yaml

# Verify the cluster is running
kubectl cluster-info --context kind-adtech-demo
kubectl get nodes
```

## Step 3: Load Docker Images into kind

Since we're using a local cluster, we need to load the images into kind:

```bash
# Load all application images
kind load docker-image quarkus-receiver:latest --name adtech-demo
kind load docker-image quarkus-receiver-native:latest --name adtech-demo
kind load docker-image go-receiver:latest --name adtech-demo
kind load docker-image rust-receiver:latest --name adtech-demo
kind load docker-image quarkus-sinker:latest --name adtech-demo

# Verify images are loaded
docker exec -it adtech-demo-control-plane crictl images | grep -E "quarkus|go-receiver|rust-receiver"
```

## Step 4: Deploy with Helm

Deploy the entire application stack using Helm:

```bash
# Install the Helm chart
helm install rtb-ingress-benchmark ./helm/rtb-ingress-benchmark

# Or use a custom namespace
helm install rtb-ingress-benchmark ./helm/rtb-ingress-benchmark --create-namespace --namespace adtech-demo

# Watch the deployment progress
kubectl get pods -n adtech-demo -w
```

## Step 5: Verify Deployment

Check that all pods are running:

```bash
# Get all pods
kubectl get pods -n adtech-demo

# Get all services
kubectl get svc -n adtech-demo

# Check pod logs
kubectl logs -f deployment/quarkus-receiver -n adtech-demo
kubectl logs -f deployment/go-receiver -n adtech-demo
kubectl logs -f deployment/rust-receiver -n adtech-demo
kubectl logs -f deployment/quarkus-sinker -n adtech-demo
```

Expected output (all pods should be Running):
```
NAME                                      READY   STATUS    RESTARTS   AGE
quarkus-receiver-xxx                      1/1     Running   0          2m
quarkus-receiver-native-xxx               1/1     Running   0          2m
go-receiver-xxx                           1/1     Running   0          2m
rust-receiver-xxx                         1/1     Running   0          2m
quarkus-sinker-xxx                        1/1     Running   0          2m
kafka-0                                   1/1     Running   0          2m
zookeeper-0                               1/1     Running   0          2m
postgresql-0                              1/1     Running   0          2m
prometheus-xxx                            1/1     Running   0          2m
grafana-xxx                               1/1     Running   0          2m
```

## Step 6: Access Services

Since we configured NodePort services with port mappings in kind, you can access services locally:

### Application Services
- **Quarkus Receiver (JVM)**: http://localhost:8070
- **Quarkus Receiver (Native)**: http://localhost:8071
- **Go Receiver**: http://localhost:8072
- **Rust Receiver**: http://localhost:8073
- **Quarkus Sinker**: http://localhost:8074

### Monitoring Services
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000
  - Username: `admin`
  - Password: `admin`

### Health Check Endpoints
```bash
# Quarkus services
curl http://localhost:8070/q/health/ready
curl http://localhost:8071/q/health/ready
curl http://localhost:8074/q/health/ready

# Go/Rust services
curl http://localhost:8072/health
curl http://localhost:8073/health
```

## Step 7: Test the Application

Send test requests to the receivers:

```bash
# Test Quarkus Receiver (JVM)
curl -X POST http://localhost:8070/bid \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "req-001",
    "publisherId": "pub-123",
    "timestamp": 1234567890,
    "deviceType": "mobile",
    "os": "iOS",
    "browser": "Safari",
    "country": "US",
    "adFormat": "banner",
    "floorPrice": 2.50
  }'

# Test Go Receiver
curl -X POST http://localhost:8072/bid \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "req-002",
    "publisherId": "pub-456",
    "timestamp": 1234567890,
    "deviceType": "desktop",
    "os": "Windows",
    "browser": "Chrome",
    "country": "UK",
    "adFormat": "video",
    "floorPrice": 5.00
  }'

# Run load test
k6 run k6/load-test.js
```

## Step 8: Monitor the Application

### View Metrics in Prometheus
1. Open http://localhost:9090
2. Try these queries:
   - `rate(http_requests_total[1m])`
   - `kafka_topic_partition_current_offset`
   - `jvm_memory_used_bytes`

### View Dashboards in Grafana
1. Open http://localhost:3000 (admin/admin)
2. Add Prometheus as a data source:
   - URL: `http://prometheus:9090`
3. Import dashboards or create custom ones

### Check PostgreSQL Data
```bash
# Connect to PostgreSQL pod
kubectl exec -it postgresql-0 -n adtech-demo -- psql -U postgres -d adtech

# Query the bids table
SELECT COUNT(*) FROM bids;
SELECT * FROM bids ORDER BY created_at DESC LIMIT 10;
```

## Configuration

### Customize Values

You can customize the deployment by editing `helm/rtb-ingress-benchmark/values.yaml` or using `--set` flags:

```bash
# Increase replicas
helm upgrade rtb-ingress-benchmark ./helm/rtb-ingress-benchmark \
  --set quarkusReceiver.replicas=3 \
  --set goReceiver.replicas=3

# Adjust resources
helm upgrade rtb-ingress-benchmark ./helm/rtb-ingress-benchmark \
  --set quarkusReceiver.resources.requests.memory=1Gi \
  --set quarkusReceiver.resources.limits.memory=2Gi

# Disable a service
helm upgrade rtb-ingress-benchmark ./helm/rtb-ingress-benchmark \
  --set rustReceiver.enabled=false
```

### Use Different Storage Class

By default, the chart uses `standard` storage class. To use a different one:

```bash
helm upgrade rtb-ingress-benchmark ./helm/rtb-ingress-benchmark \
  --set postgresql.persistence.storageClass=local-path \
  --set kafka.persistence.storageClass=local-path
```

## Troubleshooting

### Pods not starting
```bash
# Check pod events
kubectl describe pod <pod-name> -n adtech-demo

# Check pod logs
kubectl logs <pod-name> -n adtech-demo

# Check resource usage
kubectl top pods -n adtech-demo
kubectl top nodes
```

### Image pull errors
```bash
# Verify images are loaded in kind
docker exec -it adtech-demo-control-plane crictl images

# Reload images if needed
kind load docker-image quarkus-receiver:latest --name adtech-demo
```

### Services not accessible
```bash
# Verify services are created
kubectl get svc -n adtech-demo

# Check port mappings in kind
docker ps | grep adtech-demo
```

### Database connection issues
```bash
# Check PostgreSQL is ready
kubectl exec -it postgresql-0 -n adtech-demo -- pg_isready

# Test connection from another pod
kubectl run -it --rm debug --image=postgres:16-alpine --restart=Never -n adtech-demo -- \
  psql -h postgres -U postgres -d adtech
```

### Kafka connection issues
```bash
# Check Kafka logs
kubectl logs kafka-0 -n adtech-demo

# Check Zookeeper logs
kubectl logs zookeeper-0 -n adtech-demo

# List Kafka topics
kubectl exec -it kafka-0 -n adtech-demo -- kafka-topics \
  --bootstrap-server localhost:9092 --list
```

## Cleanup

### Uninstall the Helm release
```bash
helm uninstall adtech-demo -n adtech-demo
```

### Delete the namespace
```bash
kubectl delete namespace adtech-demo
```

### Delete the kind cluster
```bash
kind delete cluster --name adtech-demo
```

## Next Steps

1. **Add Horizontal Pod Autoscaling (HPA)**
   ```bash
   kubectl autoscale deployment quarkus-receiver -n adtech-demo \
     --cpu-percent=70 --min=2 --max=10
   ```

2. **Set up Ingress Controller**
   ```bash
   # Install nginx-ingress
   kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
   
   # Enable ingress in values.yaml
   helm upgrade rtb-ingress-benchmark ./helm/rtb-ingress-benchmark \
     --set ingress.enabled=true
   ```

3. **Configure Persistent Volumes**
   - Use proper storage classes for production
   - Set up backup strategies for databases

4. **Implement Monitoring Alerts**
   - Configure Prometheus alerting rules
   - Set up Alertmanager for notifications

5. **Security Hardening**
   - Use Secrets for sensitive data (passwords, API keys)
   - Implement NetworkPolicies
   - Use RBAC for access control

## Additional Resources

- [kind Documentation](https://kind.sigs.k8s.io/)
- [Helm Documentation](https://helm.sh/docs/)
- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [Quarkus Kubernetes Guide](https://quarkus.io/guides/deploying-to-kubernetes)
