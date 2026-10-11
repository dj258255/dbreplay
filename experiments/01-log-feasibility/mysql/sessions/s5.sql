BEGIN /* s5.1 */;
INSERT /* s5.2 */ INTO audit(note) VALUES ('c');
CREATE /* s5.3 */ TABLE tmp_x (id INT PRIMARY KEY);
INSERT /* s5.4 */ INTO audit(note) VALUES ('d');
ROLLBACK /* s5.5 */;
DROP /* s5.6 */ TABLE tmp_x;
