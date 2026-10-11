"""Apply the PesaGuard schema to the configured PostgreSQL instance.

The target database is supplied via the DATABASE_URL environment variable.
No credentials are embedded in source (PesaGuard Rule #14 / #59).
"""
import os
import sys

os.environ.setdefault("PYTHON_DOTENV_DISABLED", "1")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from sqlalchemy import create_engine, text

database_url = os.environ.get("DATABASE_URL")
if not database_url or not database_url.strip():
    sys.stderr.write(
        "DATABASE_URL must be set to the target database before applying the schema.\n"
    )
    raise SystemExit(2)

engine = create_engine(database_url)

from models import Base

Base.metadata.create_all(engine)

with engine.connect() as conn:
    n = conn.execute(
        text("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")
    ).scalar()
    has_tx = conn.execute(
        text("SELECT count(*) FROM information_schema.tables WHERE table_name = 'transactions'")
    ).scalar()

print(f"schema applied; public tables: {n}; transactions table present: {bool(has_tx)}")
