CALL /* s6.1 */ transfer(5, 3, 7);
PREPARE /* s6.2 */ st FROM 'UPDATE /* s6.2p */ accounts SET balance = balance + ? WHERE id = ?';
SET /* s6.3 */ @amt = 2, @id = 5;
EXECUTE /* s6.4 */ st USING @amt, @id;
DEALLOCATE /* s6.5 */ PREPARE st;
SELECT /* s6.6 */ * FROM no_such_table;
BEGIN /* s6.7 */;
UPDATE /* s6.8 */ accounts SET balance = balance + 1 WHERE id = 5;
