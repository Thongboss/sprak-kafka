package com.example;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.spark.sql.*;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

public class StreamingApp {
    public static void main(String[] args) throws TimeoutException, StreamingQueryException {
        // Ensure output and warehouse directories exist
        java.nio.file.Paths.get("/app/output/csv").toFile().mkdirs();
        java.nio.file.Paths.get("/app/output/checkpoint").toFile().mkdirs();
        java.nio.file.Paths.get("/app/output/warehouse").toFile().mkdirs();

        // Auto-create Kafka topic if not exists
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            NewTopic newTopic = new NewTopic("demo-topic", 1, (short)1);
            adminClient.createTopics(Collections.singleton(newTopic)).all().get();
            System.out.println("Topic 'demo-topic' created or already exists");
        } catch (Exception e) {
            System.out.println("Topic creation skipped: " + e.getMessage());
        }

        // Build Spark session
        SparkSession spark = SparkSession
                .builder()
                .appName("KafkaSparkStreaming")
                .master("local[*]")
                .config("spark.sql.catalogImplementation", "in-memory")
                .config("spark.sql.warehouse.dir", "/app/output/warehouse")
                .getOrCreate();

        spark.sparkContext().setLogLevel("WARN");

        // Define JSON schema
        StructType schema = new StructType()
                .add("id", DataTypes.IntegerType)
                .add("msg", DataTypes.StringType);

        // Read stream from Kafka
        Dataset<Row> kafkaStream = spark
                .readStream()
                .format("kafka")
                .option("kafka.bootstrap.servers", "kafka:9092")
                .option("subscribe", "demo-topic")
                .option("startingOffsets", "earliest")
                .load();

        Dataset<Row> jsonData = kafkaStream
                .selectExpr("CAST(value AS STRING)")
                .select(functions.from_json(functions.col("value"), schema).as("data"))
                .select("data.*");

        // Write to console
        StreamingQuery consoleQuery = jsonData.writeStream()
                .format("console")
                .outputMode("append")
                .option("truncate", "false")
                .start();

        // Write to CSV
        StreamingQuery csvQuery = jsonData.writeStream()
                .format("csv")
                .option("path", "/app/output/csv")
                .option("checkpointLocation", "/app/output/checkpoint")
                .outputMode("append")
                .start();

        consoleQuery.awaitTermination();
        csvQuery.awaitTermination();
    }
}