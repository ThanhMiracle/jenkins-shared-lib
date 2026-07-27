# Jenkins CI/CD cho Docker Compose trên EC2 Auto Scaling Group

Pipeline được gọi bằng `ec2AsgCiCd(...)` và xử lý toàn bộ ứng dụng frontend +
backend trong một job.

## Luồng release

1. Checkout và validate Docker/Compose.
2. Build backend source hiện tại thành image CI và chạy `pytest` trong image đó.
3. Build hai image frontend và backend để kiểm tra Dockerfile và dependency.
4. Quét `HIGH,CRITICAL` bằng Trivy nếu bật `trivyEnabled`.
5. Khi build nhánh `main`, validate các biến deploy và AWS CLI.
6. Đóng gói `docker-compose.prod.yml` cùng nginx config, rồi upload bundle lên S3.
7. Tạo Launch Template version mới với user-data của release.
8. Cập nhật ASG và chờ Instance Refresh hoàn tất. EC2 instance sẽ lấy `.env`
   từ SSM Parameter Store rồi chạy `docker compose up -d`.

## Cách dùng

Copy [Jenkinsfile mẫu](../examples/Jenkinsfile.ec2-asg) vào root của repository ứng
dụng và sửa các giá trị cho đúng hạ tầng. Jenkinsfile hiện tải shared library
trực tiếp với identifier `micro-lib@main`.

Repository ứng dụng cần có:

```text
.
├── Jenkinsfile
├── docker-compose.yml
├── docker-compose.prod.yml
├── frontend/Dockerfile
├── backend/Dockerfile
└── nginx/nginx.conf
```

Compose production có thể giữ nguyên như hiện tại. Pipeline sẽ copy
`docker-compose.prod.yml` thành `docker-compose.yml` trong bundle, rồi EC2
instance dùng file đó trực tiếp với các image tag cố định đã khai báo trong
compose.

## Jenkins agent

Agent cần có:

- Docker Engine và Docker Compose v2
- AWS CLI v2
- Trivy (hoặc đặt `trivyEnabled: false`)
- quyền chạy Docker

Jenkins credentials:

- `github-pat`: credential đọc GitHub repositories
- `aws-prod`: loại AWS Credentials (cần plugin AWS Credentials)

Nếu Jenkins chạy trên EC2/ECS có IAM role, bỏ hẳn `awsCredentialsId` khỏi
Jenkinsfile. Đây là cách được khuyến nghị vì không lưu access key trong Jenkins.

Khai báo ở Jenkins global environment hoặc folder properties:

```text
AWS_REGION=ap-southeast-1
AWS_CREDENTIALS_ID=aws-prod
DEPLOY_ARTIFACT_BUCKET=<S3 bucket name>
DEPLOY_ENV_PARAMETER=/my-app/prod/docker-env
DEPLOY_ASG_NAME=<Auto Scaling Group name>
DEPLOY_LAUNCH_TEMPLATE_ID=lt-xxxxxxxxxxxxxxxxx
```

`AWS_CREDENTIALS_ID` có thể để trống khi agent dùng IAM role. Bốn biến
`DEPLOY_*` chỉ bắt buộc khi build nhánh `main`; branch khác vẫn có thể chạy CI
trước khi hạ tầng production được tạo.

## Jenkins job

Loại job đúng là **Multibranch Pipeline**. Có thể chạy `seed-job` để tạo tự động
job `docker-mb`, hoặc tạo thủ công với repository
`https://github.com/ThanhMiracle/docker.git` và Script Path `Jenkinsfile`.

Các plugin cần thiết được liệt kê trong
[`jenkins/plugins.txt`](../jenkins/plugins.txt).

## Chuẩn bị AWS

Tạo một S3 bucket riêng chứa deployment bundle. Tạo SSM SecureString chứa nguyên
nội dung `.env` mà Compose sử dụng:

```bash
aws ssm put-parameter \
  --name /my-app/prod/docker-env \
  --type SecureString \
  --overwrite \
  --value file://.env
```

EC2 instance profile gắn vào Launch Template cần tối thiểu các quyền:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject"],
      "Resource": ["arn:aws:s3:::my-app-deployment-artifacts/releases/*"]
    },
    {
      "Effect": "Allow",
      "Action": ["ssm:GetParameter"],
      "Resource": ["arn:aws:ssm:ap-southeast-1:ACCOUNT_ID:parameter/my-app/prod/docker-env"]
    },
    {
      "Effect": "Allow",
      "Action": ["kms:Decrypt"],
      "Resource": ["KMS_KEY_ARN"]
    }
  ]
}
```

Không cần quyền `kms:Decrypt` nếu parameter dùng AWS-managed key mặc định.
Docker Hub images phải public; bootstrap hiện chỉ `pull` theo image tags đã ghi
trong compose file, không login Docker Hub.

IAM identity của Jenkins cần các action:

- `s3:PutObject` cho `BUCKET/releases/*`
- `ec2:DescribeLaunchTemplates`
- `ec2:CreateLaunchTemplateVersion`
- `ec2:ModifyLaunchTemplate`
- `autoscaling:DescribeAutoScalingGroups`
- `autoscaling:UpdateAutoScalingGroup`
- `autoscaling:StartInstanceRefresh`
- `autoscaling:DescribeInstanceRefreshes`
- `elasticloadbalancing:DescribeTargetGroups`
- `elasticloadbalancing:DescribeLoadBalancers`
- `elasticloadbalancing:DescribeListeners`

Launch Template nên dùng Amazon Linux 2023 và đã gắn EC2 instance profile ở trên.
ASG nên gắn vào Application Load Balancer target group với health-check endpoint
của Nginx/API. Security Group của EC2 chỉ nên cho phép port 80 từ Security Group
của ALB. User-data sẽ cài Docker, AWS CLI và Docker Compose nếu AMI chưa có.

## Lưu ý về biến môi trường frontend

Deploy stage tự tìm ALB gắn với target group đầu tiên của ASG. Bootstrap ghi
`API_BASE=http(s)://<alb-dns>/api` vào `.env`; HTTPS được chọn khi ALB có HTTPS
listener. Frontend hiện tại đọc giá trị này lúc container khởi động qua
`env.js`. Các biến backend còn lại vẫn được lấy từ SSM Parameter Store.
