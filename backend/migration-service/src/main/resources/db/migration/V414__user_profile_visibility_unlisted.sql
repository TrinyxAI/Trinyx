-- V414: introduce the UNLISTED profile visibility and migrate existing rows to it.
--
-- profile_visibility used to be PUBLIC | PRIVATE, where PUBLIC meant, over time,
-- two different things: "visible to logged-in users inside the app" and later
-- "readable by anyone holding the link". It never meant "indexed by search
-- engines". Adding a third state makes indexing an explicit choice instead of a
-- third silent widening of the same stored value.
--
-- Every existing PUBLIC row therefore becomes UNLISTED: the page keeps working
-- exactly as it does today (reachable by link, linked from the author's
-- listings) and simply is not advertised to search engines. Leaving them as
-- PUBLIC would opt every current user into search indexing of a personal page
-- retroactively, which is a consent decision none of them made.
--
-- Users who WANT to be discoverable set PUBLIC themselves, from the profile
-- settings. PRIVATE rows are untouched.
UPDATE auth.user_profiles
SET profile_visibility = 'UNLISTED'
WHERE profile_visibility = 'PUBLIC';

-- New rows default to UNLISTED for the same reason (the entity default matches).
ALTER TABLE auth.user_profiles
    ALTER COLUMN profile_visibility SET DEFAULT 'UNLISTED';
