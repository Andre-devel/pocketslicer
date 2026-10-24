#include "i18n.hpp"

#include <fstream>
#include <stdexcept>
#include <unordered_map>

#include "libslic3r/I18N.hpp"

namespace slicer::i18n {

namespace {

std::unordered_map<std::string, std::string> catalog;

// Conteúdo de uma string C entre aspas (sem elas), com os escapes do gettext.
std::string unquote(const std::string &line, size_t begin)
{
    std::string out;
    const size_t end = line.rfind('"');
    for (size_t i = begin + 1; i < end; ++i) {
        if (line[i] != '\\' || i + 1 >= end) {
            out += line[i];
            continue;
        }
        switch (line[++i]) {
        case 'n': out += '\n'; break;
        case 't': out += '\t'; break;
        default: out += line[i]; // \" \\ e outros
        }
    }
    return out;
}

// Entradas de um .po. Ficam de fora as com contexto (msgctxt: o libslic3r só usa
// L_CONTEXT como marcador, sem traduzir), as de plural, as "fuzzy" e as sem tradução.
void parse(std::istream &in, std::unordered_map<std::string, std::string> &out)
{
    std::string msgid, msgstr, line;
    std::string *field = nullptr;
    bool skip = false, has_id = false;
    auto flush = [&] {
        if (has_id && !skip && !msgid.empty() && !msgstr.empty())
            out[msgid] = msgstr;
        msgid.clear();
        msgstr.clear();
        field = nullptr;
        skip = has_id = false;
    };
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r')
            line.pop_back();
        if (line.empty()) {
            flush();
        } else if (line.rfind("#,", 0) == 0) {
            if (line.find("fuzzy") != std::string::npos)
                skip = true;
        } else if (line[0] == '#') {
            continue;
        } else if (line[0] == '"') {
            if (field != nullptr)
                *field += unquote(line, 0);
        } else if (line.rfind("msgctxt ", 0) == 0 || line.rfind("msgid_plural ", 0) == 0) {
            skip = true;
            field = nullptr;
        } else if (line.rfind("msgid ", 0) == 0) {
            if (has_id)
                flush();
            has_id = true;
            msgid = unquote(line, line.find('"'));
            field = &msgid;
        } else if (line.rfind("msgstr ", 0) == 0) {
            msgstr = unquote(line, line.find('"'));
            field = &msgstr;
        } else {
            field = nullptr; // msgstr[n] de plurais
        }
    }
    flush();
}

std::string translate_callback(const char *text)
{
    return translate(text);
}

} // namespace

void load(const std::vector<std::string> &po_files)
{
    std::unordered_map<std::string, std::string> entries;
    for (const std::string &path : po_files) {
        std::ifstream in(path);
        if (!in)
            throw std::runtime_error("cannot read " + path);
        parse(in, entries);
    }
    catalog = std::move(entries);
    Slic3r::I18N::set_translate_callback(catalog.empty() ? nullptr : &translate_callback);
}

std::string translate(const std::string &text)
{
    auto it = catalog.find(text);
    return it == catalog.end() ? text : it->second;
}

} // namespace slicer::i18n
