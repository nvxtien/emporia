CREATE TABLE workspace_preference (
    user_subject VARCHAR(200) PRIMARY KEY,
    layout_json TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
