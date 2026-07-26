# Jenkins CI/CD – Full-stack FE/BE trên EC2 ASG

Shared Library này dành cho một ứng dụng gồm hai service:

- `frontend`: SPA frontend
- `api`: backend FastAPI

Pipeline `ec2AsgCiCd(...)` chạy test, build và push hai Docker images, sau đó
rolling deploy lên EC2 Auto Scaling Group bằng Launch Template và Instance
Refresh.

## Cấu trúc

```text
.
├── envs/                         # Cấu hình seed job theo môi trường
├── examples/Jenkinsfile.ec2-asg # Jenkinsfile để copy sang repo ứng dụng
├── resources/org/ci/             # EC2 bootstrap script
├── src/org/ci/                   # Validation cấu hình pipeline
├── vars/ec2AsgCiCd.groovy        # Full-stack CI/CD pipeline
├── seed.groovy                   # Tạo một multibranch job duy nhất
└── docs/ec2-asg-cicd.md          # Hướng dẫn Jenkins và AWS
```

## Cài đặt nhanh

1. Khai báo repository này trong Jenkins Global Pipeline Libraries với tên
   `jenkins-shared-lib`.
2. Copy [Jenkinsfile mẫu](examples/Jenkinsfile.ec2-asg) vào repository ứng dụng.
3. Sửa Docker context, credentials ID và các AWS resource ID.
4. Chạy `seed-job` với `TARGET_ENV=dev`, `staging` hoặc `prod`.

Seed job đọc source từ `https://github.com/ThanhMiracle/docker.git` và tạo đúng
một multibranch job: `docker-<environment>-mb`.

Xem [hướng dẫn đầy đủ](docs/ec2-asg-cicd.md) để cấu hình S3, SSM, IAM, Launch
Template, ALB và Auto Scaling Group.
