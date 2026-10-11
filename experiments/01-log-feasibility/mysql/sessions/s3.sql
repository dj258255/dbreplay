SELECT /* s3.1 */ COUNT(*) FROM accounts;
UPDATE /* s3.2 */ accounts SET balance = balance + 1 WHERE id = 3;
INSERT /* s3.3 */ INTO audit(note) VALUES ('a');
SELECT /* s3.4 */ * FROM accounts WHERE id = 3;
