DO /* s2.1 */ SLEEP(0.2);
START TRANSACTION /* s2.2 */;
UPDATE /* s2.3 */ accounts SET balance = balance - 5 WHERE id = 1;
SELECT /* s2.4 */ balance FROM accounts WHERE id = 1 FOR UPDATE;
ROLLBACK /* s2.5 */;
