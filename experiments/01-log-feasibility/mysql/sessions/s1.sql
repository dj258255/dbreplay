BEGIN /* s1.1 */;
UPDATE /* s1.2 */ accounts SET balance = balance - 10 WHERE id = 1;
DO /* s1.3 */ SLEEP(0.6);
UPDATE /* s1.4 */ accounts SET balance = balance + 10 WHERE id = 2;
COMMIT /* s1.5 */;
SELECT /* s1.6 */ balance FROM accounts WHERE id = 1;
