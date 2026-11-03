# Contribution and deployment flow

Direct pushes and force pushes to `staging` and `main` are blocked. Changes must go through pull requests.

1. Create a feature branch from `staging` and open a pull request into `staging`.
2. Review the Preview deployment at `https://2flygarecalender-pre.vercel.app` after the pull request is merged into `staging`.
3. When the release is approved, open a pull request from `staging` into `main`.
4. Merge that pull request to trigger the production deployment at `https://2flygarecalender.vercel.app`.

The branch policy workflow rejects pull requests into `main` unless their source is `staging`, and rejects pull requests into `staging` from `main`.
