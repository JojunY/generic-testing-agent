alter table customer add column tier varchar(10) default 'BASIC' not null;
-- SEEDED DEFECT D9: > instead of >=; a customer with exactly 100 points stays BASIC
update customer set tier = 'GOLD' where points > 100;
