-- Immutable, encrypted metadata snapshots. No provider credentials or file contents.
create table if not exists public.uth_snapshots (
 id uuid primary key,
 user_id uuid not null references auth.users(id) on delete cascade,
 device_id uuid not null,
 format integer not null default 1 check (format = 1),
 payload text not null check (octet_length(payload) <= 20971520),
 created_at timestamptz not null default now()
);
create index if not exists uth_snapshots_owner_created on public.uth_snapshots(user_id, created_at desc);
alter table public.uth_snapshots enable row level security;
revoke all on public.uth_snapshots from anon, authenticated;
grant select, insert, delete on public.uth_snapshots to authenticated;
create policy "Read own snapshots" on public.uth_snapshots for select to authenticated using ((select auth.uid()) = user_id);
create policy "Append own snapshots" on public.uth_snapshots for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "Delete own snapshots" on public.uth_snapshots for delete to authenticated using ((select auth.uid()) = user_id);
