import pg from 'pg';
import { readFile } from 'node:fs/promises';

export async function createDatabase(env = process.env) {
  if (!env.PGPASSWORD_FILE) throw new Error('PasswordFileRequired');
  const password = (await readFile(env.PGPASSWORD_FILE, 'utf8')).replace(/\r?\n$/, '');
  if (!password) throw new Error('EmptyDatabasePassword');
  const pool = new pg.Pool({
    host: env.PGHOST ?? 'db', port: 5432,
    database: env.PGDATABASE ?? 'vebetter_scoring', user: env.PGUSER ?? 'scoring_owner',
    password, max: 3, connectionTimeoutMillis: 5000, idleTimeoutMillis: 10000,
  });
  pool.on('error', () => console.error(JSON.stringify({event: 'database_pool_error'})));
  return {
    async withJobLock(jobId,operation) {
      const guard=await pool.connect();
      let held=false,guardError=null;
      const onError=error=>{guardError=error;};guard.on('error',onError);
      try {
        held=(await guard.query('SELECT pg_try_advisory_lock(hashtextextended($1,0)) AS held',[jobId])).rows[0].held;
        if(!held)throw new Error('JobAlreadyRunning');
        const result=await operation();
        if(guardError)throw new Error('JobLockConnectionLost');
        return result;
      }finally{
        if(held&&!guardError)await guard.query('SELECT pg_advisory_unlock(hashtextextended($1,0))',[jobId]).catch(()=>{});
        guard.removeListener('error',onError);guard.release(guardError??undefined);
      }
    },
    async transaction(operation) {
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        await client.query("SET LOCAL statement_timeout = '120s'");
        await client.query("SET LOCAL lock_timeout = '5s'");
        const result = await operation(client);
        await client.query('COMMIT');
        return result;
      } catch (error) {
        await client.query('ROLLBACK').catch(() => {});
        throw error;
      } finally { client.release(); }
    },
    close: () => pool.end(),
  };
}
