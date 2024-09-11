DO
$$
    BEGIN
        CREATE ROLE proletarian WITH LOGIN;
    EXCEPTION
        WHEN duplicate_object THEN
            RAISE NOTICE 'The proletarian role already exists';
    END
$$;
