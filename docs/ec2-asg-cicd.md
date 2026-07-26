# Jenkins CI/CD cho Docker Compose trên EC2 Auto Scaling Group

Pipeline được gọi bằng `ec2AsgCiCd(...)` và xử lý toàn bộ ứng dụng frontend +
backend trong một job.

## Luồng release

1. Checkout và validate Docker/Compose/AWS CLI.
2. Build backend source hiện tại thành image CI và chạy `pytest` trong image đó.
3. Build hai image frontend và backend, tag bằng 12 ký tự Git SHA.
4. Quét `HIGH,CRITICAL` bằng Trivy.
5. Khi build nhánh `main`, login và push image lên Docker Hub.
6. Tạo Compose override trỏ đúng image SHA và upload bundle lên S3.
7. Tạo Launch Template version mới với user-data của release.
8. Cập nhật ASG và chờ Instance Refresh hoàn tất. AWS tự rollback nếu refresh lỗi.

## Cách dùng

Copy [Jenkinsfile mẫu](../examples/Jenkinsfile.ec2-asg) vào root của repository ứng
dụng và sửa các giá trị cho đúng hạ tầng. Trong Jenkins, khai báo shared library
với tên `jenkins-shared-lib`.

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

Compose production có thể giữ nguyên như hiện tại. Pipeline tạo
`docker-compose.release.yml` để override image frontend/API theo Git SHA, vì vậy
không deploy bằng tag mutable `latest`.

## Jenkins agent

Agent cần có:

- Docker Engine và Docker Compose v2
- AWS CLI v2
- Trivy (hoặc đặt `trivyEnabled: false`)
- quyền chạy Docker

Jenkins credentials:

- `dockerhub-creds`: loại Username with password
- `aws-prod`: loại AWS Credentials (cần plugin AWS Credentials)

Nếu Jenkins chạy trên EC2/ECS có IAM role, bỏ hẳn `awsCredentialsId` khỏi
Jenkinsfile. Đây là cách được khuyến nghị vì không lưu access key trong Jenkins.

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
Docker Hub images phải public; nếu là private, bootstrap cần thêm cơ chế lấy
Docker credentials từ Secrets Manager.

IAM identity của Jenkins cần các action:

- `s3:PutObject` cho `BUCKET/releases/*`
- `ec2:DescribeLaunchTemplates`
- `ec2:CreateLaunchTemplateVersion`
- `ec2:ModifyLaunchTemplate`
- `autoscaling:UpdateAutoScalingGroup`
- `autoscaling:StartInstanceRefresh`
- `autoscaling:DescribeInstanceRefreshes`

Launch Template nên dùng Amazon Linux 2023 và đã gắn EC2 instance profile ở trên.
ASG nên gắn vào Application Load Balancer target group với health-check endpoint
của Nginx/API. Security Group của EC2 chỉ nên cho phép port 80 từ Security Group
của ALB. User-data sẽ cài Docker, AWS CLI và Docker Compose nếu AMI chưa có.

## Lưu ý về biến môi trường frontend

Với SPA, biến như `API_BASE` thường được đóng vào bundle ở lúc build, không được
đọc khi container đã chạy. Nếu frontend của bạn thuộc loại này, truyền
`--build-arg API_BASE=...` trong bước build hoặc dùng cấu hình runtime
`env.js`. Backend vẫn nhận `.env` bình thường từ Compose.
