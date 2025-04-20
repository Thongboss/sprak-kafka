package com.example;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.spark.sql.*;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.streaming.Trigger;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.apache.spark.sql.functions.*;

public class StreamingApp {
    public static void main(String[] args) throws StreamingQueryException, TimeoutException {
        // Ensure base output directory exists
        try {
            Files.createDirectories(Paths.get("/app/output"));
        } catch (Exception e) {
            System.err.println("Cannot create output dir: " + e.getMessage());
        }

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

        // Initialize Spark session
        SparkSession spark = SparkSession.builder()
                .appName("OnDemandKafkaCSV")
                .master("local[*]")
                .getOrCreate();
        spark.sparkContext().setLogLevel("WARN");

        // Define Student JSON schema
        StructType schema = new StructType()
                .add("studentCd", DataTypes.StringType)
                .add("studentName", DataTypes.StringType)
                .add("studentMark", DataTypes.DoubleType)
                .add("dateOfBirth", DataTypes.StringType)
                .add("studentClass", DataTypes.StringType);

        // Read stream from Kafka
        Dataset<Row> kafkaStream = spark.readStream()
                .format("kafka")
                .option("kafka.bootstrap.servers", "kafka:9092")
                .option("subscribe", "demo-topic")
                .option("startingOffsets", "earliest")
                .option("failOnDataLoss", "false")
                .load();

        // Parse JSON into columns
        Dataset<Row> students = kafkaStream
                .selectExpr("CAST(value AS STRING) as json")
                .select(from_json(col("json"), schema).as("data"))
                .select("data.*");

        // Write on-demand one batch, single CSV file named by date
        StreamingQuery query = students.writeStream()
                .foreachBatch((batchDF, batchId) -> {
                    String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                    String tempDir = "/app/output/tmp-" + date;

                    // Write CSV to temporary directory
                    batchDF.coalesce(1)
                            .write()
                            .mode("overwrite")
                            .option("header", "true")
                            .csv(tempDir);

                    // Move part file to final named CSV
                    Path dir = Paths.get(tempDir);
                    try {
                        Path part = Files.list(dir)
                                .filter(p -> p.getFileName().toString().startsWith("part-"))
                                .findFirst()
                                .orElseThrow(() -> new RuntimeException("Part file not found"));
                        Path dest = Paths.get("/app/output/" + date + ".csv");
                        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        System.err.println("Error moving CSV file: " + e.getMessage());
                    }

                    // Delete temporary directory
                    try (Stream<Path> walk = Files.walk(dir)) {
                        walk.sorted(Comparator.reverseOrder())
                                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                    }

                    System.out.println("Written file: " + date + ".csv");
                })
                .option("checkpointLocation", "/app/output/checkpoint-once")
                .trigger(Trigger.Once())
                .start();

        query.awaitTermination();
        spark.stop();
    }
}