- lệnh clear target: mvn clean
- lệnh build package: mvn build
- lệnh trỏ vào kafka-container: docker run -it --rm --network sprak-kafka_default confluentinc/cp-kafka:7.4.0 bash
- lệnh chạy all-service: docker-compose up --build -d
- lệnh tắt all-service: docker-compose down -v
- lệnh chạy riêng service spark-app: docker-compose up spark-app
- lệnh check net-work: docker network ls

