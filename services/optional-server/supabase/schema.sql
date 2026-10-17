-- Deploy only to a dedicated UnderTheHood project. Metadata contains no provider secrets.
begin;
create table if not exists public.library_events (
  seq bigint generated always as identity,
  owner_id uuid not null references auth.users(id) on delete cascade,
  event_id uuid not null,
  device_id text not null check (length(device_id) between 1 and 256),
  payload jsonb not null check (jsonb_typeof(payload) = 'object' and octet_length(payload::text) <= 65536),
  created_at timestamptz not null default now(),
  primary key (owner_id, event_id),
  unique (seq)
);
create index if not exists library_events_owner_seq on public.library_events(owner_id,seq);
alter table public.library_events enable row level security;
revoke all on public.library_events from anon, authenticated;
grant select, insert, delete on public.library_events to authenticated;
grant usage on sequence public.library_events_seq_seq to authenticated;
create policy "read own events" on public.library_events for select to authenticated using ((select auth.uid()) = owner_id);
create policy "append own events" on public.library_events for insert to authenticated with check ((select auth.uid()) = owner_id);
create policy "delete own events" on public.library_events for delete to authenticated using ((select auth.uid()) = owner_id);
commit;
