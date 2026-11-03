create table external_identity (
    provider varchar(32) not null,
    subject varchar(255) not null,
    user_id bigint not null references app_user(id),
    primary key(provider, subject),
    unique(provider, user_id)
);
