-- Central migration V2: sequence for changes written directly on the central server.
-- Central-originated writes join the same applied_changes/global_seq timeline that edges pull,
-- using their own dense origin sequence so (origin_node_id, origin_seq) stays unique.
CREATE SEQUENCE IF NOT EXISTS central_origin_seq START WITH 1 MINVALUE 1;
