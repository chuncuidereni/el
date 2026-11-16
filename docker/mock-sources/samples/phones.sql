CREATE DATABASE IF NOT EXISTS demo;
CREATE TABLE IF NOT EXISTS demo.phones_plain (
  order_id BIGINT,
  user_id BIGINT,
  amount DECIMAL(12,2),
  status STRING,
  phone STRING
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS TEXTFILE
TBLPROPERTIES ('skip.header.line.count'='1');
CREATE TABLE IF NOT EXISTS demo.phones_md5 (
  order_id BIGINT,
  user_id BIGINT,
  amount DECIMAL(12,2),
  status STRING,
  phone_md5 STRING
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS TEXTFILE
TBLPROPERTIES ('skip.header.line.count'='1');
LOAD DATA LOCAL INPATH '/samples/phones_plain.csv' OVERWRITE INTO TABLE demo.phones_plain;
LOAD DATA LOCAL INPATH '/samples/phones_md5.csv' OVERWRITE INTO TABLE demo.phones_md5;
SELECT COUNT(*) AS plain_count FROM demo.phones_plain;
SELECT COUNT(*) AS md5_count FROM demo.phones_md5;
SELECT COUNT(*) AS matching_count FROM demo.phones_plain p
JOIN demo.phones_md5 h ON p.order_id=h.order_id
WHERE md5(p.phone)=h.phone_md5;
