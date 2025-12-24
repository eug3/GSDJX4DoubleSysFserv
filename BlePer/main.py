from typing import Optional


def main(argv: Optional[list[str]] = None) -> int:
    from http_image_server import main as web_main

    return web_main(argv)


if __name__ == "__main__":
    raise SystemExit(main())
