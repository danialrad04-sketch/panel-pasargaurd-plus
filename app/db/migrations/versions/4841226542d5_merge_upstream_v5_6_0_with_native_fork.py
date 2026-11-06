"""merge upstream v5.6.0 with native fork

Revision ID: 4841226542d5
Revises: e7b8c9d0e1f2, 1a2b3c4d5e6f
Create Date: 2026-11-06 10:18:04.267780

"""

# revision identifiers, used by Alembic.
revision = "4841226542d5"
down_revision = ("e7b8c9d0e1f2", "1a2b3c4d5e6f")
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Pure merge point: upstream's v5.6.0 chain and the fork's native chain touch different tables.
    pass


def downgrade() -> None:
    pass
