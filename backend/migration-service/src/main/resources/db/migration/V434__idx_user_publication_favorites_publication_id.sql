-- V434: index the favorites table by publication_id.
--
-- The marketplace browse lists now order by a popularity score whose heaviest
-- term is "how many users favorited this app" (see
-- PublicationListQueryService.POPULARITY_ORDER_BY and
-- WorkflowPublicationRepository.POPULARITY_ORDER_JPQL/SQL). That is a per-row
-- correlated COUNT on publication_id.
--
-- V359 only created the (user_id, organization_id, created_at DESC) index and a
-- PK on (user_id, organization_id, publication_id): with publication_id as the
-- trailing key column, neither can serve a lookup BY publication_id, so every
-- marketplace page would seq-scan the favorites table once per publication row.
CREATE INDEX IF NOT EXISTS idx_user_pub_favorites_publication
    ON publication.user_publication_favorites (publication_id);
