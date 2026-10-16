CREATE UNIQUE INDEX operations_one_original_create_idx ON operations (environment_id)
    WHERE type = 'CREATE_ENVIRONMENT';
