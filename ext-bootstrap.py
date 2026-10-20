#!/usr/bin/env python3

import argparse
import re
from pathlib import Path
from urllib.parse import urlparse

LIB_VERSION = "2.0"


def ascii_validator(value: str) -> str:
    value = value.strip()
    try:
        value.encode("ascii")
    except UnicodeEncodeError:
        raise argparse.ArgumentTypeError(f"'{value}' is not ASCII")

    return value


def validate_args(args: argparse.Namespace, parser: argparse.ArgumentParser):
    # Validate language
    lang = args.lang
    if not re.fullmatch(r"(?:[a-z]{2,3}(?:-[A-Z]{2,3})?|all)", lang):
        parser.error(
            f"invalid language: '{lang}' (must be a 2- or 3-letter ISO language code)"
        )

    # Validate base URL
    baseurl = urlparse(args.baseurl)
    if baseurl.scheme != "https" or not baseurl.netloc:
        parser.error(
            f"invalid baseurl: '{baseurl}' (must include scheme 'https://' and a host)"
        )
    args.baseurl = f"{baseurl.scheme}://{baseurl.netloc}"

    # Validate extension repo path
    path = Path(args.path).resolve()
    if not path.is_dir():
        parser.error(f"invalid path: '{path}' (directory does not exist)")
    elif "common" not in [p.name for p in path.iterdir()]:
        parser.error(f"invalid path: '{path}' (not a valid extension repo directory)")
    args.path = path

    # Validate multisrc theme if provided
    args.lib_version = LIB_VERSION
    if args.multisrc:
        multisrc_dir = path / "lib-multisrc"
        if not multisrc_dir.exists() or not multisrc_dir.is_dir():
            parser.error(f"No multisrc found in extension repo path: '{path}'")

        multisrc_theme = args.multisrc.lower()
        if multisrc_theme not in [p.name for p in multisrc_dir.iterdir() if p.is_dir()]:
            parser.error(
                f"invalid multisrc theme: '{multisrc_theme}' (not found in '{multisrc_dir}')"
            )
        args.multisrc = multisrc_theme

        # Extensions must use the same libVersion as their theme
        theme_lib = re.search(
            r'libVersion = "([^"]+)"',
            (multisrc_dir / multisrc_theme / "build.gradle.kts").read_text(),
        )
        if theme_lib:
            args.lib_version = theme_lib.group(1)


def get_ext_classname(extname: str) -> str:
    sanitized = re.sub(r"[^A-Za-z0-9 ]", "", extname)
    return "".join(word.capitalize() for word in re.findall(r"[A-Za-z0-9]+", sanitized))


def write_gradle_file(args: argparse.Namespace, ext_dir: Path) -> None:
    with open(ext_dir / "build.gradle.kts", "w") as f:
        f.write("import app.dokusho.gradle.api.ContentWarning\n\n")
        f.write("plugins {\n")
        f.write("\talias(dok.plugins.extension)\n")
        f.write("}\n\n")
        f.write("dokusho {\n")
        f.write(f'\tname = "{args.extname}"\n')

        if args.multisrc:
            f.write(f'\ttheme = "{args.multisrc}"\n')
            f.write("\tversionCode = 0\n")
        else:
            f.write("\tversionCode = 1\n")

        f.write(f"\tcontentWarning = ContentWarning.{args.content_warning}\n")
        f.write(f'\tlibVersion = "{args.lib_version}"\n\n')

        f.write("\tsource {\n")
        if args.source_name:
            f.write(f'\t\tname = "{args.source_name}"\n')

        f.write(f'\t\tbaseUrl = "{args.baseurl}"\n')
        f.write(f'\t\tlang = "{args.lang}"\n')
        f.write("\t}\n")

        if not args.multisrc:
            f.write("\n\tdeeplink {\n")
            f.write('\t\tpath("/..*")\n')
            f.write("\t}\n")

        f.write("}\n")


def write_multisrc_source(f, args: argparse.Namespace, classname: str) -> None:
    multisrc_classname = get_ext_classname(args.multisrc)
    f.write(
        f"import eu.kanade.tachiyomi.multisrc.{args.multisrc}.{multisrc_classname}\n"
    )
    f.write("import dokusho.annotation.Source\n\n")
    f.write("@Source\n")
    f.write(f"abstract class {classname} : {multisrc_classname}()\n")


def write_keisource_source(f, classname: str) -> None:
    """Novel extension skeleton: listing, details with chapters, and sanitized chapter text."""
    f.write(
        """import dokusho.annotation.Source
import dokusho.network.get
import dokusho.network.rateLimit
import dokusho.source.DokushoSource
import dokusho.utils.asJsoup
import eu.kanade.tachiyomi.source.model.ChapterContent
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

"""
    )
    f.write("@Source\n")
    f.write(f"abstract class {classname} : DokushoSource() {{\n")
    f.write(
        """
    // Run /ext-recon first and replace every selector below with what the site really uses.

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Referer", "$baseUrl/")

    override suspend fun getPopularManga(page: Int): MangasPage =
        parseListing(client.get("$baseUrl/popular?page=$page", headers).asJsoup(), page)

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        parseListing(client.get("$baseUrl/latest?page=$page", headers).asJsoup(), page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage =
        parseListing(client.get("$baseUrl/search?q=$query&page=$page", headers).asJsoup(), page)

    private fun parseListing(document: Document, page: Int): MangasPage {
        val novels = document.select(".novel-item").mapNotNull { card ->
            val link = card.selectFirst("a") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }
        return MangasPage(novels, hasNextPage = document.selectFirst("a[rel=next]") != null)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), headers).asJsoup()
        val details = if (!fetchDetails) {
            manga
        } else {
            SManga.create().apply {
                url = manga.url
                title = document.selectFirst("h1")?.text() ?: manga.title
                author = document.selectFirst(".author")?.text()
                description = document.selectFirst(".description")?.text()
                genre = document.select(".genres a").eachText().joinToString()
                status = SManga.UNKNOWN
                initialized = true
            }
        }
        // Newest chapter first. Follow pagination if the site splits the list.
        val chapterList = if (!fetchChapters) {
            chapters
        } else {
            document.select(".chapter-list a").map { link ->
                SChapter.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    name = link.text()
                }
            }.reversed()
        }
        return SMangaUpdate(details, chapterList)
    }

    override suspend fun getChapterContent(chapter: SChapter): ChapterContent {
        val response = client.get(getChapterUrl(chapter), headers, ensureSuccess = false)
        if (response.code == 401 || response.code == 403) {
            response.close()
            return ChapterContent(html = "", locked = true)
        }
        val document = response.asJsoup()
        val body = document.selectFirst(".chapter-content")
            ?: return ChapterContent(html = "", title = chapter.name, locked = true)
        body.select("script, style, iframe, ins, noscript, .ads, [style*=display:none]").remove()
        body.select("a, span").unwrap()
        return ChapterContent(html = body.html(), title = document.selectFirst("h1")?.text())
    }
}
"""
    )


def write_source_file(
    args: argparse.Namespace,
    ext_package_dir: Path,
    ext_dir_lang: str,
    ext_dir_name: str,
) -> None:
    classname = get_ext_classname(args.extname)

    with open(ext_package_dir / f"{classname}.kt", "w") as f:
        f.write(
            f"package eu.kanade.tachiyomi.extension.{ext_dir_lang}.{ext_dir_name}\n\n"
        )

        if args.multisrc:
            write_multisrc_source(f, args, classname)
        else:
            write_keisource_source(f, classname)


if __name__ == "__main__":
    argparser = argparse.ArgumentParser()
    argparser.add_argument(
        "-n", "--extname", type=ascii_validator, help="Extension name", required=True
    )
    argparser.add_argument(
        "--source-name",
        type=str,
        help="Source Name (defaults to extname)",
        required=False,
    )
    argparser.add_argument(
        "-l",
        "--lang",
        "--language",
        type=ascii_validator,
        help="Extension language",
        required=True,
    )
    argparser.add_argument(
        "-u",
        "--baseurl",
        type=ascii_validator,
        help="Extension BaseUrl (must be an https URL)",
        required=True,
    )
    argparser.add_argument(
        "-c",
        "--content-warning",
        choices=("SAFE", "MIXED", "NSFW"),
        type=str.upper,
        default="SAFE",
        help="Content warning level",
    )
    argparser.add_argument(
        "-m",
        "--multisrc",
        type=ascii_validator,
        help="multisrc theme",
        required=False,
    )
    argparser.add_argument(
        "--path",
        type=str,
        help="Path to extension repo directory (defaults to cwd)",
        required=False,
        default=".",
    )
    args = argparser.parse_args()
    validate_args(args, argparser)

    ext_dir_name = re.sub(r"[^A-Za-z0-9]", "", args.extname).lower()
    ext_dir_lang = args.lang.split("-")[0]
    ext_dir = args.path / "src" / ext_dir_lang / ext_dir_name

    if ext_dir.exists():
        print(f"Extension directory already exists: '{ext_dir}'")
        exit(1)

    ext_dir.mkdir(parents=True)
    print(f"Created extension directory: '{ext_dir}'")

    write_gradle_file(args, ext_dir)

    ext_package_dir = (
        ext_dir
        / "src"
        / "eu"
        / "kanade"
        / "tachiyomi"
        / "extension"
        / ext_dir_lang
        / ext_dir_name
    )
    ext_package_dir.mkdir(parents=True)
    print(f"Created extension package directory: '{ext_package_dir}'")

    write_source_file(args, ext_package_dir, ext_dir_lang, ext_dir_name)
    print(f"Created source file: '{ext_package_dir}'")
