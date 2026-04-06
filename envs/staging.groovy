[
    repoUrl: 'https://github.com/ThanhMiracle/micro-ecom.git',
    credentialsId: 'github-token',
    services: [
        [name: 'auth',    scriptPath: 'services/auth-service/Jenkinsfile'],
        [name: 'product', scriptPath: 'services/product-service/Jenkinsfile'],
        [name: 'order',   scriptPath: 'services/order-service/Jenkinsfile'],
        [name: 'payment', scriptPath: 'services/payment-service/Jenkinsfile'],
        [name: 'notify',  scriptPath: 'services/notification-service/Jenkinsfile']
    ]
]