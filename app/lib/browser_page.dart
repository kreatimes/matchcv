import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'api.dart';
import 'store.dart';
import 'widgets.dart';

// URL de búsqueda de InfoJobs a partir de una palabra clave.
// Solo ponemos lo básico; el resto de filtros los elige el usuario en la propia web de InfoJobs.
String infojobsSearchUrl(String keyword, String? province) {
  final params = {'keyword': keyword, 'sortBy': 'PUBLICATION_DATE', 'page': '1'};
  // ponytail: solo conocemos el id de Madrid (33); otras provincias se eligen en los filtros de InfoJobs
  if ((province ?? '').toLowerCase().contains('madrid')) {
    params['provinceIds'] = '33';
  } else {
    params['countryIds'] = '17'; // toda España
  }
  return Uri.https('www.infojobs.net', '/jobsearch/search-results/list.xhtml', params).toString();
}

// Texto visible de la página + sus enlaces (para que la IA asocie cada oferta con su URL)
const _extractJs = r'''
JSON.stringify({
  text: document.body.innerText.slice(0, 60000),
  links: Array.from(document.querySelectorAll('a[href]'))
    .map(a => ({ text: (a.innerText || '').trim().slice(0, 150), href: a.href }))
    .filter(l => l.text.length > 4)
    .slice(0, 400)
})
''';

/// Navegador dentro de la app: el usuario navega por el portal y pide analizar lo que está viendo.
class BrowserPage extends StatefulWidget {
  final Profile profile;
  final String name, role, portal, url;

  const BrowserPage({
    super.key,
    required this.profile,
    required this.name,
    required this.role,
    required this.portal,
    required this.url,
  });

  @override
  State<BrowserPage> createState() => _BrowserPageState();
}

class _BrowserPageState extends State<BrowserPage> {
  late final WebViewController _web;
  bool _pageLoading = true;
  bool _analyzing = false;

  @override
  void initState() {
    super.initState();
    _web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setNavigationDelegate(NavigationDelegate(
        onPageStarted: (_) {
          if (mounted) setState(() => _pageLoading = true);
        },
        onPageFinished: (_) {
          if (mounted) setState(() => _pageLoading = false);
        },
      ))
      ..loadRequest(Uri.parse(widget.url));
  }

  Future<void> _save() async {
    final url = await _web.currentUrl() ?? widget.url;
    if (!mounted) return;
    final name = await askText(context, title: 'Guardar búsqueda', initial: widget.name, hint: 'Nombre');
    if (name == null) return;
    await Store.addSearch(SavedSearch(name: name, role: widget.role, portal: widget.portal, url: url));
    if (mounted) showMessage(context, 'Búsqueda guardada con los filtros actuales');
  }

  Future<void> _analyze() async {
    setState(() => _analyzing = true);
    try {
      // Android devuelve el resultado como texto JSON entrecomillado: puede hacer falta decodificar dos veces
      Object? data = await _web.runJavaScriptReturningResult(_extractJs);
      if (data is String) data = jsonDecode(data);
      if (data is String) data = jsonDecode(data);
      final page = data as Map<String, dynamic>;

      final matches = await Api.analyzePage(
        profile: widget.profile,
        role: widget.role,
        portal: widget.portal,
        text: page['text'] as String,
        links: page['links'] as List<dynamic>,
      );
      if (mounted) _showResults(matches);
    } catch (e) {
      debugPrint('Error analizando página: $e');
      if (mounted) showMessage(context, Api.errorMessage(e));
    } finally {
      if (mounted) setState(() => _analyzing = false);
    }
  }

  void _showResults(List<Match> matches) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.75,
        maxChildSize: 0.95,
        builder: (context, scroll) => ListView(
          controller: scroll,
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          children: [
            Text(
              matches.isEmpty
                  ? 'No hay ofertas en esta página'
                  : '${matches.length} ofertas en esta página · '
                      '${matches.where((m) => m.score >= 70).length} encajan bien contigo',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            ...matches.map((m) => MatchCard(
                  match: m,
                  // Abrimos la oferta en el mismo navegador
                  onOpen: (url) {
                    Navigator.pop(context);
                    _web.loadRequest(Uri.parse(url));
                  },
                )),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    // El botón "atrás" vuelve a la página anterior del portal; si no hay, sale del navegador
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        if (await _web.canGoBack()) {
          await _web.goBack();
        } else if (context.mounted) {
          Navigator.pop(context);
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(widget.name),
          bottom: _pageLoading
              ? const PreferredSize(preferredSize: Size.fromHeight(2), child: LinearProgressIndicator(minHeight: 2))
              : null,
          actions: [
            IconButton(tooltip: 'Guardar búsqueda', icon: const Icon(Icons.bookmark_add_outlined), onPressed: _save),
          ],
        ),
        body: WebViewWidget(controller: _web),
        // Barra fija debajo de la web: así el botón nunca tapa nada de la página
        bottomNavigationBar: SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: FilledButton.icon(
              onPressed: _analyzing || _pageLoading ? null : _analyze,
              icon: _analyzing
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.auto_awesome),
              label: Text(_analyzing ? 'Analizando…' : 'Analizar página'),
            ),
          ),
        ),
      ),
    );
  }
}
