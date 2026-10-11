DROP TABLE IF EXISTS accounts, audit, tmp_x;
DROP PROCEDURE IF EXISTS transfer;
CREATE TABLE accounts (id INT PRIMARY KEY, balance INT NOT NULL);
INSERT INTO accounts VALUES (1,100),(2,100),(3,100),(4,100),(5,100);
CREATE TABLE audit (id INT AUTO_INCREMENT PRIMARY KEY, note VARCHAR(64));
DELIMITER //
CREATE PROCEDURE transfer(IN a INT, IN b INT, IN amt INT)
BEGIN
  START TRANSACTION;
  UPDATE /* proc.1 */ accounts SET balance = balance - amt WHERE id = a;
  UPDATE /* proc.2 */ accounts SET balance = balance + amt WHERE id = b;
  COMMIT;
END//
DELIMITER ;
