# Jenkins CI/CD – Full-stack FE/BE trên EC2 ASG

Shared Library này dành cho một ứng dụng gồm hai service:

- `frontend`: SPA frontend
- `api`: backend FastAPI

Pipeline `ec2AsgCiCd(...)` chạy test, build hai Docker images để kiểm tra, rồi
đóng gói `docker-compose.prod.yml` và nginx config thành bundle để EC2 Auto
Scaling Group pull và chạy bằng `docker compose`.

## Cấu trúc

```text
.
├── examples/Jenkinsfile.ec2-asg # Jenkinsfile để copy sang repo ứng dụng
├── resources/org/ci/             # EC2 bootstrap script
├── src/org/ci/                   # Validation cấu hình pipeline
├── vars/ec2AsgCiCd.groovy        # Full-stack CI/CD pipeline
├── jenkins/plugins.txt            # Jenkins plugins bắt buộc
├── seed.groovy                   # Tạo một multibranch job duy nhất
└── docs/ec2-asg-cicd.md          # Hướng dẫn Jenkins và AWS
```

## Cài đặt nhanh

1. Tạo Jenkins credentials `github-pat` và (nếu cần) `aws-prod`.
2. Copy [Jenkinsfile mẫu](examples/Jenkinsfile.ec2-asg) vào repository ứng dụng.
3. Khai báo các environment variables AWS được liệt kê trong tài liệu.
4. Chạy `seed-job`; Jenkinsfile tự tải shared library với tên `micro-lib@main`.

Seed job đọc source từ `https://github.com/ThanhMiracle/docker.git` và tạo đúng
một multibranch job: `docker-mb`. Mọi branch chạy CI; chỉ `main` tạo bundle và
deploy lên EC2 ASG.

Xem [hướng dẫn đầy đủ](docs/ec2-asg-cicd.md) để cấu hình S3, SSM, IAM, Launch
Template, ALB và Auto Scaling Group.
