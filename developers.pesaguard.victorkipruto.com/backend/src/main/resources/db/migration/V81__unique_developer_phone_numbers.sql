create unique index users_phone_number_unique
    on users (phone_number)
    where phone_number is not null;
