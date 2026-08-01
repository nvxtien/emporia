UPDATE user_account
SET desk = username
WHERE desk = 'default';

UPDATE user_account account
SET can_trade = TRUE
WHERE EXISTS (
    SELECT 1
    FROM user_authority authority
    WHERE authority.user_id = account.id
      AND authority.authority = 'ROLE_ADMIN'
);
