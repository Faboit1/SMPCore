-- combat: kill log by time, bounties by state
-- Startup reads the counted kills of the last day back into the repeated-pair cache, and the bounty book loads the
-- active contributions; both would scan their whole table without these.
CREATE INDEX idx_kills_counted_ts ON kills (counted, ts);
CREATE INDEX idx_bounties_state ON bounties (state, created);
