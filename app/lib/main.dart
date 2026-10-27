import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import 'api.dart';
import 'browser_page.dart';
import 'store.dart';
import 'widgets.dart';

void main() => runApp(const MatchCvApp());

class MatchCvApp extends StatelessWidget {
  const MatchCvApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MatchCV',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      darkTheme: ThemeData(colorSchemeSeed: Colors.indigo, brightness: Brightness.dark, useMaterial3: true),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  bool _loading = true;
  bool _uploading = false;
  Profile? _profile;
  List<SavedSearch> _searches = [];

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    final profile = await Store.loadProfile();
    final searches = await Store.loadSearches();
    if (!mounted) return;
    setState(() {
      _profile = profile;
      _searches = searches;
      _loading = false;
    });
  }

  Future<void> _uploadCv() async {
    final picked = await FilePicker.pickFiles(type: FileType.custom, allowedExtensions: ['pdf']);
    final file = picked.isEmpty ? null : picked.first;
    if (file == null || file.path == null) return;

    setState(() => _uploading = true);
    try {
      final profile = await Api.profile(file.path!, file.name);
      await Store.saveProfile(profile);
      setState(() => _profile = profile);
    } catch (e) {
      debugPrint('Error subiendo CV: $e');
      if (mounted) showMessage(context, Api.errorMessage(e));
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _open({required String name, required String role, required String url}) async {
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => BrowserPage(profile: _profile!, name: name, role: role, portal: 'InfoJobs', url: url),
      ),
    );
    _reload(); // por si ha guardado búsquedas
  }

  Future<void> _customSearch() async {
    final keyword = await askText(context, title: 'Nueva búsqueda', hint: 'Ej.: ayudante de cocina');
    if (keyword == null) return;
    _open(name: keyword, role: keyword, url: infojobsSearchUrl(keyword, _profile!.province));
  }

  Future<void> _delete(SavedSearch search) async {
    final list = _searches.where((s) => s != search).toList();
    await Store.saveSearches(list);
    setState(() => _searches = list);
  }

  @override
  Widget build(BuildContext context) {
    final profile = _profile;
    return Scaffold(
      appBar: AppBar(
        title: const Text('MatchCV'),
        actions: [
          if (profile != null && !_uploading)
            IconButton(tooltip: 'Cambiar CV', icon: const Icon(Icons.upload_file), onPressed: _uploadCv),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _uploading
              ? const _Loading()
              : profile == null
                  ? _Start(onPick: _uploadCv)
                  : _home(profile),
    );
  }

  Widget _home(Profile p) {
    final text = Theme.of(context).textTheme;
    final savedNames = _searches.map((s) => s.name.toLowerCase()).toSet();
    final suggestions = p.suggestions.where((s) => !savedNames.contains(s.name.toLowerCase())).toList();
    final details = [?p.seniority, if (p.years != null) '${p.years} años exp.', ?p.province].join(' · ');

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Tu perfil', style: text.labelLarge),
                const SizedBox(height: 4),
                Text(p.role, style: text.titleLarge),
                if (details.isNotEmpty) Text(details, style: text.bodyMedium),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: p.skills
                      .take(10)
                      .map((s) => Chip(label: Text(s), visualDensity: VisualDensity.compact))
                      .toList(),
                ),
              ],
            ),
          ),
        ),
        if (_searches.isNotEmpty) ...[
          _Header('Tus búsquedas'),
          ..._searches.map((s) => Card(
                child: ListTile(
                  leading: const Icon(Icons.bookmark),
                  title: Text(s.name),
                  subtitle: Text(s.portal),
                  trailing: IconButton(
                    tooltip: 'Borrar',
                    icon: const Icon(Icons.delete_outline),
                    onPressed: () => _delete(s),
                  ),
                  onTap: () => _open(name: s.name, role: s.role, url: s.url),
                ),
              )),
        ],
        _Header('Sugeridas por tu CV'),
        ...suggestions.map((s) => Card(
              child: ListTile(
                leading: const Icon(Icons.auto_awesome),
                title: Text(s.name),
                subtitle: Text('Buscar "${s.keyword}" en InfoJobs'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _open(name: s.name, role: s.role, url: infojobsSearchUrl(s.keyword, p.province)),
              ),
            )),
        Card(
          child: ListTile(
            leading: const Icon(Icons.search),
            title: const Text('Otra búsqueda'),
            subtitle: const Text('Escribe lo que quieras buscar'),
            onTap: _customSearch,
          ),
        ),
        const SizedBox(height: 8),
        Text(
          'Abre una búsqueda, ajusta los filtros en InfoJobs y pulsa el marcador para guardarla. '
          'Con "Analizar página" la IA puntúa las ofertas que estás viendo.',
          style: text.bodySmall,
        ),
      ],
    );
  }
}

class _Header extends StatelessWidget {
  final String text;

  const _Header(this.text);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 16, 4, 8),
      child: Text(text, style: Theme.of(context).textTheme.titleSmall),
    );
  }
}

class _Start extends StatelessWidget {
  final VoidCallback onPick;

  const _Start({required this.onPick});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Icon(Icons.work_outline, size: 72, color: Theme.of(context).colorScheme.primary),
              const SizedBox(height: 16),
              Text('Encuentra ofertas que encajan contigo', style: text.headlineSmall, textAlign: TextAlign.center),
              const SizedBox(height: 8),
              Text(
                'Sube tu CV en PDF. La IA te propone búsquedas y, mientras navegas por InfoJobs, '
                'te dice cuánto encajas en cada oferta y qué te falta.',
                style: text.bodyMedium,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              FilledButton.icon(
                onPressed: onPick,
                icon: const Icon(Icons.upload_file),
                label: const Padding(padding: EdgeInsets.all(12), child: Text('Subir mi CV')),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Loading extends StatelessWidget {
  const _Loading();

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          CircularProgressIndicator(),
          SizedBox(height: 16),
          Text('Leyendo tu CV…'),
        ],
      ),
    );
  }
}
