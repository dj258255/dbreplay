SET /* s4.1 */ autocommit = 0;
UPDATE /* s4.2 */ accounts SET balance = balance + 1 WHERE id = 4;
INSERT /* s4.3 */ INTO audit(note) VALUES ('b');
COMMIT /* s4.4 */;
SELECT /* s4.5 */ balance FROM accounts WHERE id = 4;
UPDATE /* s4.6 */ accounts SET balance = balance + 1 WHERE id = 4;
ROLLBACK /* s4.7 */;
SET /* s4.8 */ autocommit = 1;
UPDATE /* s4.9 */ accounts SET balance = balance + 1 WHERE id = 4;
